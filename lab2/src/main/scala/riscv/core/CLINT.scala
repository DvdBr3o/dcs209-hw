// Copyright 2021 Howard Lau
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package riscv.core

import chisel3._
import chisel3.util.{MuxLookup, Cat}
import riscv.Parameters

object InterruptCode {
  val None = 0x0.U(8.W)
  val Timer0 = 0x1.U(8.W)
  val Ret = 0xFF.U(8.W)
}

object InterruptEntry {
  val Timer0 = 0x4.U(8.W)
}


class CSRDirectAccessBundle extends Bundle {
  val mstatus = Input(UInt(Parameters.DataWidth))
  val mepc = Input(UInt(Parameters.DataWidth))
  val mcause = Input(UInt(Parameters.DataWidth))
  val mtvec = Input(UInt(Parameters.DataWidth))

  val mstatus_write_data= Output(UInt(Parameters.DataWidth))
  val mepc_write_data= Output(UInt(Parameters.DataWidth))
  val mcause_write_data= Output(UInt(Parameters.DataWidth))

  val direct_write_enable = Output(Bool())
}

// Core Local Interrupt Controller
class CLINT extends Module {
  val io = IO(new Bundle {
    // Interrupt signals from peripherals
    val interrupt_flag = Input(UInt(Parameters.InterruptFlagWidth))

    val instruction = Input(UInt(Parameters.InstructionWidth))
    val instruction_address = Input(UInt(Parameters.AddrWidth))

    val jump_flag = Input(Bool())
    val jump_address = Input(UInt(Parameters.AddrWidth))

    val interrupt_handler_address = Output(UInt(Parameters.AddrWidth))
    val interrupt_assert = Output(Bool())

    val csr_bundle = new CSRDirectAccessBundle
  })
  val interrupt_enable = io.csr_bundle.mstatus(3)
  val instruction_address = Mux(
    io.jump_flag,
    io.jump_address,
    io.instruction_address + 4.U,
  )
  //lab2(CLINTCSR)
  val mpie = io.csr_bundle.mstatus(7)
  val mie = io.csr_bundle.mstatus(3)
  // val mpp = io.csr_bundle.mstatus(12, 11)

  // diffic1: mstatus too complicated
  // diffic2: mcause not known, then found not written

  when(io.interrupt_flag =/= InterruptCode.None && interrupt_enable) { // interrupt
    io.interrupt_assert := true.B
    io.interrupt_handler_address := io.csr_bundle.mtvec
    io.csr_bundle.mstatus_write_data :=
      Cat(
        io.csr_bundle.mstatus(31, 8), 
        mie, // mpie
        io.csr_bundle.mstatus(6, 4), 
        0.U(1.W), //mie
        io.csr_bundle.mstatus(2, 0)
      )
    io.csr_bundle.mepc_write_data := instruction_address
    io.csr_bundle.mcause_write_data := 
      Cat(
        1.U, 
        MuxLookup(
          io.interrupt_flag, 
          11.U(31.W) // machine external interrupt
          )(IndexedSeq(
            InterruptCode.Timer0 -> 7.U(31.W),
          )
        )
      )
    io.csr_bundle.direct_write_enable := true.B
  }.elsewhen(io.instruction === InstructionsEnv.ebreak || io.instruction === InstructionsEnv.ecall) { // exception
    io.interrupt_assert := true.B
    io.interrupt_handler_address := io.csr_bundle.mtvec
    io.csr_bundle.mstatus_write_data := 
      Cat(
        io.csr_bundle.mstatus(31, 8), 
        mie, // mpie
        io.csr_bundle.mstatus(6, 4), 
        0.U(1.W), //mie
        io.csr_bundle.mstatus(2, 0)
      )
    io.csr_bundle.mepc_write_data := instruction_address
    io.csr_bundle.mcause_write_data := Cat(0.U, MuxLookup(io.instruction, 0.U)(IndexedSeq(
      InstructionsEnv.ebreak -> 3.U(31.W),
      InstructionsEnv.ecall -> 11.U(31.W),
    )))
    io.csr_bundle.direct_write_enable := true.B
  }.elsewhen(io.instruction === InstructionsRet.mret) { // ret
    io.interrupt_assert := true.B
    io.interrupt_handler_address := io.csr_bundle.mepc
    io.csr_bundle.mstatus_write_data := 
      Cat(
        io.csr_bundle.mstatus(31, 8),
        1.U(1.W),
        io.csr_bundle.mstatus(6, 4),
        mpie, //mie
        io.csr_bundle.mstatus(2, 0)
      )
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause
    io.csr_bundle.direct_write_enable := true.B
  }.otherwise {
    io.interrupt_assert := false.B
    io.interrupt_handler_address := io.csr_bundle.mtvec
    io.csr_bundle.mstatus_write_data := io.csr_bundle.mstatus
    io.csr_bundle.mepc_write_data := io.csr_bundle.mepc
    io.csr_bundle.mcause_write_data := io.csr_bundle.mcause
    io.csr_bundle.direct_write_enable := false.B
  }
  // io.interrupt_handler_address := io.csr_bundle.mepc
}
