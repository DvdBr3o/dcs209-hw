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

package riscv

import scala.util.control.Breaks._
import chisel3._
import chisel3.util._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import java.nio.{ByteBuffer, ByteOrder}

import riscv.{Parameters, TestAnnotations}
import peripheral._
import bus.BusSwitch
import riscv.core.CPU
import riscv.ImplementationType



class TestInstructionROM(asmBin: String) extends Module {
  val io = IO(new Bundle {
    val address = Input(UInt(32.W))
    val data = Output(UInt(32.W))
  })

  val (insts, capacity) = loadAsmBinary(asmBin)
  val mem = RegInit(insts)
  io.data := mem(io.address)

  def loadAsmBinary(filename: String) = {
    val inputStream = getClass.getClassLoader.getResourceAsStream(filename)
    var instructions = new Array[BigInt](0)
    val arr = new Array[Byte](4)
    while (inputStream.read(arr) == 4) {
      val instBuf = ByteBuffer.wrap(arr)
      instBuf.order(ByteOrder.LITTLE_ENDIAN)
      val inst = BigInt(instBuf.getInt() & 0xFFFFFFFFL)
      instructions = instructions :+ inst
    }
    (VecInit((instructions.map(inst => inst.U(32.W))).toIndexedSeq), instructions.length)
  }
}

object BootStates extends ChiselEnum {
  val Init, Loading, Finished = Value
}

class TestTopModule(exeFilename: String) extends Module {
  val io = IO(new Bundle {
    val regs_debug_read_address = Input(UInt(Parameters.PhysicalRegisterAddrWidth))
    val regs_debug_read_data = Output(UInt(Parameters.DataWidth))
    val mem_debug_read_address = Input(UInt(Parameters.AddrWidth))
    val mem_debug_read_data = Output(UInt(Parameters.DataWidth))

    val interrupt = Input(UInt(Parameters.InterruptFlagWidth))
  })

  val boot_state = RegInit(BootStates.Init)
  val instruction_rom = Module(new TestInstructionROM(exeFilename))
  val rom_loader = Module(new ROMLoader(instruction_rom.capacity))
  val mem = Module(new Memory(8192))
  
  val timer = Module(new peripheral.Timer)
  val bus_switch = Module(new BusSwitch)
  val dummy = Module(new DummySlave)

  // bus connections
  for (i <- 0 until Parameters.SlaveDeviceCount) {
    bus_switch.io.slaves(i) <> dummy.io.channels
  }

  rom_loader.io.load_address := Parameters.EntryAddress
  rom_loader.io.rom_data := instruction_rom.io.data
  rom_loader.io.load_start := false.B
  instruction_rom.io.address := rom_loader.io.rom_address

  bus_switch.io.slaves(0) <> mem.io.channels
  rom_loader.io.channels <> dummy.io.channels
  bus_switch.io.slaves(4) <> timer.io.channels

  switch(boot_state) {
    is(BootStates.Init) {
      boot_state := BootStates.Loading
      rom_loader.io.load_start := true.B
      rom_loader.io.channels <> mem.io.channels
    }
    is(BootStates.Loading) {
      rom_loader.io.load_start := false.B
      rom_loader.io.channels <> mem.io.channels
      when(rom_loader.io.load_finished) {
        boot_state := BootStates.Finished
      }
    }
  }

  val reset_cpu = (reset.asBool) || (boot_state =/= BootStates.Finished)
  withReset (reset_cpu) {   // this ensures CPU is up only after program is loaded
    val cpu = Module(new CPU)
    bus_switch.io.master <> cpu.io.axi4_channels
    bus_switch.io.address := cpu.io.bus_address
    cpu.io.debug_read_address := io.regs_debug_read_address
    io.regs_debug_read_data := cpu.io.debug_read_data
    cpu.io.interrupt_flag := io.interrupt

    // UART print
    val device = cpu.io.bus_address(Parameters.AddrBits - 1, Parameters.AddrBits - Parameters.SlaveDeviceCountBits)
    when (cpu.io.debug_bus_write_enable && device === 2.U) {
      printf("%c", cpu.io.debug_bus_write_data.asUInt)
    }
  }

  mem.io.debug_read_address := io.mem_debug_read_address
  io.mem_debug_read_data := mem.io.debug_read_data
}


class FiveStageCPUTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Five-stage Pipelined CPU"

  it should "calculate recursively fibonacci(10)" in {
    test(new TestTopModule("fibonacci.asmbin")).withAnnotations(TestAnnotations.annos) { c =>
      c.clock.setTimeout(100 * 1000)
      c.io.interrupt.poke(0.U)
      c.io.mem_debug_read_address.poke(0.U)

      var cycle_passed = 0
      breakable { while (true) {
        c.clock.step()
        cycle_passed += 1
        if (c.io.mem_debug_read_data.peek().litValue == 0xbabecafeL) {
          c.clock.step(100)
          break()
        }
      }}

      c.io.mem_debug_read_address.poke(4.U)
      c.clock.step()
      c.io.mem_debug_read_data.expect(55.U)

      print(s"Cycles passed: $cycle_passed")
    }
  }

  it should "quicksort 10 numbers" in {
    test(new TestTopModule("quicksort.asmbin")).withAnnotations(TestAnnotations.annos) { c =>
      c.clock.setTimeout(50 * 1000)
      c.io.interrupt.poke(0.U)
      c.io.mem_debug_read_address.poke(0.U)

      var cycle_passed = 0
      breakable { while (true) {
        c.clock.step()
        cycle_passed += 1
        if (c.io.mem_debug_read_data.peek().litValue == 0xbabecafeL) {
          c.clock.step(100)
          break()
        }
      }}

      for (i <- 1 to 10) {
        c.io.mem_debug_read_address.poke((4 * i).U)
        c.clock.step()
        c.io.mem_debug_read_data.expect((i - 1).U)
      }

      print(s"Cycles passed: $cycle_passed")
    }
  }

  it should "store and load single byte" in {
    test(new TestTopModule("sb.asmbin")).withAnnotations(TestAnnotations.annos) { c =>
      c.clock.step(500)
      c.io.regs_debug_read_address.poke(5.U)
      c.io.regs_debug_read_data.expect(0xDEADBEEFL.U)
      c.io.regs_debug_read_address.poke(6.U)
      c.io.regs_debug_read_data.expect(0xEF.U)
      c.io.regs_debug_read_address.poke(1.U)
      c.io.regs_debug_read_data.expect(0x15EF.U)
    }
  }

  it should "read and write timer register" in {
    test(new TestTopModule("mmio.asmbin")).withAnnotations(TestAnnotations.annos) { c =>
      c.io.interrupt.poke(0.U)
      c.clock.setTimeout(2000)
      c.clock.step(1000)
      c.io.regs_debug_read_address.poke(5.U)
      c.io.regs_debug_read_data.expect(100000000.U)
      c.io.regs_debug_read_address.poke(6.U)
      c.io.regs_debug_read_data.expect(0xBEEF.U)
    }
  } 
}
