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

package riscv.core.fivestage_final

import chisel3._
import chisel3.util._
import riscv.Parameters
import riscv.core.BusBundle
import chisel3.util.switch

object ProgramCounter {
  val EntryAddress = Parameters.EntryAddress
}

object IFFetchStates extends ChiselEnum {
  val INIT_READ,    // in this state, it will send a read request immediately when bus is available, regardless of stall signal
  WAIT_RESP,        // wait for previous read response
  WAIT_BUS_AVAIL,   // after receiving read response, wait for bus to be available to send next read request; skip if already available
  WAIT_BUS_GRANT    // when bus grant is revoked, wait for bus grant signal
  = Value
}

class InstructionFetch extends Module {
  val io = IO(new Bundle {
    val in_stall_flag = Input(Bool()) 
    val jump_flag_id = Input(Bool())
    val jump_address_id = Input(UInt(Parameters.AddrWidth))

    val instruction_address = Output(UInt(Parameters.AddrWidth))
    val id_instruction = Output(UInt(Parameters.InstructionWidth))
    val out_stall_flag = Output(Bool()) // stall when instruction fetch is not finished

    val bus = new BusBundle
  })
  val pc = RegInit(ProgramCounter.EntryAddress)
  val fetched_instruction = RegInit(InstructionsNop.nop) // store fetched instruction temporarily

  pc := MuxCase(
    pc + 4.U,
    IndexedSeq(
      (io.jump_flag_id) -> io.jump_address_id,  // NOTE: jump flag may assert when stalling
      (io.in_stall_flag) -> pc
    )
  )
  io.instruction_address := pc

  // defaults for bus
  io.bus.write := false.B
  io.bus.write_data := 0.U
  io.bus.write_strobe := VecInit(Seq.fill(Parameters.WordSize)(false.B))

  /* ------------- IF fetch from memory -------------
    IF fetch part perform fetching from memory. It sends a read request once bus is available, 
    and will not send another request until the previous one is finished.
    Upon jump_flag asserted, it has 2 cases:
    1. if the previous request is finished, and the next request hasn't been sent(or to send at this moment),
      then it will send the next request to the jump address.
    2. if the previous request is not finished, then it will wait until the previous request is finished,
      and then send the next request to the jump address. The previous request is invalidated by IF receive and push part.
  
    IF fetch part loop in 3 states:
      SEND_REQUEST(1 cycle) -> WAIT_FOR_RESPONSE(0~N cycles) -> WAIT_BUS_AVAILABLE(0~N cycles) -> SEND_REQUEST...
  */

  val fetch_state = RegInit(IFFetchStates.INIT_READ)
  val post_read = RegInit(false.B) // signal for 1 cycle read transaction init
  val prev_read_invalid = RegInit(false.B)  // write by fetch part, read by push part
  io.bus.request := post_read
  io.bus.read := post_read
  io.bus.address := pc

  when (post_read) {
    post_read := false.B  // signal lasts for 1 cycle
  }

  when (io.jump_flag_id) {
    // you have to invalidate previous read or finished read at this moment, and update for next read
    prev_read_invalid := true.B
    // PC updated to jump address
  }

  def send_read_req = {
    post_read := true.B
    fetch_state := IFFetchStates.WAIT_RESP
  }

  val bus_available = !io.bus.busy && io.bus.granted 

  switch (fetch_state) {
    is (IFFetchStates.INIT_READ) {
      when (bus_available) {
        send_read_req
      }
    }

    is (IFFetchStates.WAIT_RESP) {
      when (io.bus.read_valid) {  // read_valid lasts for 1 cycle only
        fetch_state := IFFetchStates.WAIT_BUS_AVAIL
        when (!io.jump_flag_id) {
          // previous read is valid, and next read is to pc + 4
          prev_read_invalid := false.B
        }

        when (bus_available) {
          send_read_req // also skip WAIT_BUS_AVAIL state
        }
      }

      when (!io.bus.granted) {
        fetch_state := IFFetchStates.WAIT_BUS_GRANT
      }
    }

    is (IFFetchStates.WAIT_BUS_AVAIL) {
      when (bus_available) {
        send_read_req
      }
    }

    is (IFFetchStates.WAIT_BUS_GRANT) {
      when (io.bus.granted) {
        fetch_state := IFFetchStates.INIT_READ
        when (bus_available) {
          send_read_req // also skip INIT_READ state
        }
      }
    }
  }


  // ------------- IF push instruction to ID -------------

  io.out_stall_flag := true.B
  io.id_instruction := Mux(io.in_stall_flag, InstructionsNop.nop, fetched_instruction) // default nop when stalling
  when (io.bus.read_valid && io.bus.granted) {  // read_valid last for 1 cycle only
    when (prev_read_invalid || io.jump_flag_id) {
      // do not push instruction to ID, just invalidate this read
    } .otherwise {
      // push instruction to ID
      io.out_stall_flag := false.B
      io.id_instruction := io.bus.read_data // immediate action
      fetched_instruction := io.bus.read_data
    }
  }



  
}
