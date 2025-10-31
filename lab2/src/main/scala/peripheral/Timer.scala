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

package peripheral

import chisel3._
import chisel3.util._
import riscv.Parameters

class Timer extends Module {
  val io = IO(new Bundle {
    val bundle = new RAMBundle
    val signal_interrupt = Output(Bool())

    val debug_limit = Output(UInt(Parameters.DataWidth))
    val debug_enabled = Output(Bool())
  })

  val count = RegInit(0.U(32.W))
  val limit = RegInit(100000000.U(32.W))
  io.debug_limit := limit
  val enabled = RegInit(true.B)
  io.debug_enabled := enabled

  //lab2(CLINTCSR)
  //finish the read-write for count,limit,enabled. And produce appropriate signal_interrupt
  object DataAddr {
    val enable = 0x8.U
    val limit = 0x4.U
  }

  io.signal_interrupt := Mux(count >= limit, true.B, false.B)
  count := Mux(count >= limit, 0.U, count + 1.U)

  io.bundle.read_data := MuxLookup(io.bundle.address, 0.U)(IndexedSeq(
    DataAddr.enable -> enabled,
    DataAddr.limit -> limit,
  ))

  when(io.bundle.write_enable) {
    when(io.bundle.address === DataAddr.enable) {
      enabled := io.bundle.write_data
    } .elsewhen(io.bundle.address === DataAddr.limit) {
      limit := io.bundle.write_data
    }
  }
}
