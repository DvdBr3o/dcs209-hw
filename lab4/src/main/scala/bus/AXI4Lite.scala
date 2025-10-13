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

package bus

import chisel3._
import chisel3.experimental.ChiselEnum
import chisel3.util._
import riscv.Parameters

object AXI4Lite {
  val protWidth = 3
  val respWidth = 2
}

class AXI4LiteWriteAddressChannel(addrWidth: Int) extends Bundle {
  val AWVALID = Output(Bool())
  val AWREADY = Input(Bool())
  val AWADDR = Output(UInt(addrWidth.W))
  val AWPROT = Output(UInt(AXI4Lite.protWidth.W))

  // NOTE: AWCACHE ignored now; AWBURST is fixed to INCR; 
}

class AXI4LiteWriteDataChannel(dataWidth: Int) extends Bundle {
  val WVALID = Output(Bool())
  val WREADY = Input(Bool())
  val WDATA = Output(UInt(dataWidth.W))
  val WSTRB = Output(UInt((dataWidth / 8).W))
  // val WLAST = Output(Bool())
}

class AXI4LiteWriteResponseChannel extends Bundle {
  val BVALID = Input(Bool())
  val BREADY = Output(Bool())
  val BRESP = Input(UInt(AXI4Lite.respWidth.W))
}

class AXI4LiteReadAddressChannel(addrWidth: Int) extends Bundle {
  val ARVALID = Output(Bool())
  val ARREADY = Input(Bool())
  val ARADDR = Output(UInt(addrWidth.W))
  val ARPROT = Output(UInt(AXI4Lite.protWidth.W))
  // NOTE: ARCACHE ignored now; ARBURST is fixed to INCR;
}

class AXI4LiteReadDataChannel(dataWidth: Int) extends Bundle {
  val RVALID = Input(Bool())
  val RREADY = Output(Bool())
  val RDATA = Input(UInt(dataWidth.W))
  val RRESP = Input(UInt(AXI4Lite.respWidth.W))
  // val RLAST = Input(Bool())
}

class AXI4LiteInterface(addrWidth: Int, dataWidth: Int) extends Bundle {
  val AWVALID = Output(Bool())
  val AWREADY = Input(Bool())
  val AWADDR = Output(UInt(addrWidth.W))
  val AWPROT = Output(UInt(AXI4Lite.protWidth.W))
  val WVALID = Output(Bool())
  val WREADY = Input(Bool())
  val WDATA = Output(UInt(dataWidth.W))
  val WSTRB = Output(UInt((dataWidth / 8).W))
  val BVALID = Input(Bool())
  val BREADY = Output(Bool())
  val BRESP = Input(UInt(AXI4Lite.respWidth.W))
  val ARVALID = Output(Bool())
  val ARREADY = Input(Bool())
  val ARADDR = Output(UInt(addrWidth.W))
  val ARPROT = Output(UInt(AXI4Lite.protWidth.W))
  val RVALID = Input(Bool())
  val RREADY = Output(Bool())
  val RDATA = Input(UInt(dataWidth.W))
  val RRESP = Input(UInt(AXI4Lite.respWidth.W))
}

class AXI4LiteChannels(addrWidth: Int, dataWidth: Int) extends Bundle {
  val write_address_channel = new AXI4LiteWriteAddressChannel(addrWidth)
  val write_data_channel = new AXI4LiteWriteDataChannel(dataWidth)
  val write_response_channel = new AXI4LiteWriteResponseChannel()
  val read_address_channel = new AXI4LiteReadAddressChannel(addrWidth)
  val read_data_channel = new AXI4LiteReadDataChannel(dataWidth)
}

// Bundle for slave device to interact with AXI4-Lite bus
class AXI4LiteSlaveBundle(addrWidth: Int, dataWidth: Int) extends Bundle {
  val address = Output(UInt(addrWidth.W))
  val read = Output(Bool())                 // tell slave device to read
  val read_data = Input(UInt(dataWidth.W))  // data read from slave device
  val read_valid = Input(Bool())            // indicates if read_data is valid, asserts for ONLY 1 cycle for each item.
  val write = Output(Bool())                // tell slave device to write
  val write_data = Output(UInt(dataWidth.W))
  val write_strobe = Output(Vec(Parameters.WordSize, Bool()))
  // NOTE: for simplicity, we assume write to slave device always succeeds, `write_valid` currently not present
}

// Bundle for master device to interact with AXI4-Lite bus
class AXI4LiteMasterBundle(addrWidth: Int, dataWidth: Int) extends Bundle {
  val address = Input(UInt(addrWidth.W))
  val read = Input(Bool())                  // request a read transaction
  val write = Input(Bool())                 // request a write transaction
  val read_data = Output(UInt(dataWidth.W))
  val write_data = Input(UInt(dataWidth.W))
  val write_strobe = Input(Vec(Parameters.WordSize, Bool()))

  val busy = Output(Bool())                 // if busy, master is not ready to accept new transactions
  val read_valid = Output(Bool())           // indicates read transaction done successfully and asserts for ONLY 1 cycle.
  val write_valid = Output(Bool())          // indicates write transaction done successfully and asserts for ONLY 1 cycle.
}

object AXI4LiteStates extends ChiselEnum {
  val Idle, ReadAddr, ReadData, WriteAddr, WriteData, WriteResp = Value
}


class AXI4LiteSlave(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val channels = Flipped(new AXI4LiteChannels(addrWidth, dataWidth))
    val bundle = new AXI4LiteSlaveBundle(addrWidth, dataWidth)
  })
  val state = RegInit(AXI4LiteStates.Idle)

  val addr = RegInit(0.U(dataWidth.W))
  io.bundle.address := addr

  // read signals
  val read = RegInit(false.B)
  io.bundle.read := read
  val read_data = RegInit(0.U(dataWidth.W))
  io.channels.read_data_channel.RDATA := read_data

  val ARREADY = RegInit(false.B)
  io.channels.read_address_channel.ARREADY := ARREADY
  val RVALID = RegInit(false.B)
  io.channels.read_data_channel.RVALID := RVALID
  val RRESP = RegInit(0.U(AXI4Lite.respWidth.W))
  io.channels.read_data_channel.RRESP := RRESP


  // write signals
  val write = RegInit(false.B)
  io.bundle.write := write
  val write_data = RegInit(0.U(dataWidth.W))
  io.bundle.write_data := write_data
  val write_strobe = RegInit(VecInit(Seq.fill(Parameters.WordSize)(false.B)))
  io.bundle.write_strobe := write_strobe

  val AWREADY = RegInit(false.B)
  io.channels.write_address_channel.AWREADY := AWREADY
  val WREADY = RegInit(false.B)
  io.channels.write_data_channel.WREADY := WREADY
  val BVALID = RegInit(false.B)
  io.channels.write_response_channel.BVALID := BVALID
  val BRESP = WireInit(0.U(AXI4Lite.respWidth.W))
  io.channels.write_response_channel.BRESP := BRESP

  // lab4(bus): slave

  switch(state) {
    is (AXI4LiteStates.Idle) {
      
    }

    is (AXI4LiteStates.ReadAddr) {
      
    }

    is (AXI4LiteStates.ReadData) {  
      
    }

    is (AXI4LiteStates.WriteAddr) {
      
    }

    is (AXI4LiteStates.WriteData) {
      
    }

    is (AXI4LiteStates.WriteResp) {
      
    }
  }

  // lab4(bus): slave End
}

class AXI4LiteMaster(addrWidth: Int, dataWidth: Int) extends Module {
  val io = IO(new Bundle {
    val channels = new AXI4LiteChannels(addrWidth, dataWidth)
    val bundle = new AXI4LiteMasterBundle(addrWidth, dataWidth)
  })
  val state = RegInit(AXI4LiteStates.Idle)
  io.bundle.busy := state =/= AXI4LiteStates.Idle

  val addr = RegInit(0.U(dataWidth.W))  // address to read/write
  io.channels.read_address_channel.ARADDR := addr
  io.channels.write_address_channel.AWADDR := addr

  // read signals
  val read_valid = RegInit(false.B)
  io.bundle.read_valid := read_valid
  val read_data = RegInit(0.U(dataWidth.W))
  io.bundle.read_data := read_data

  val ARVALID = RegInit(false.B)
  io.channels.read_address_channel.ARVALID := ARVALID
  val RREADY = RegInit(false.B)
  io.channels.read_data_channel.RREADY := RREADY

  io.channels.read_address_channel.ARPROT := 0.U


  // write signals
  val write_valid = RegInit(false.B)
  io.bundle.write_valid := write_valid
  val write_data = RegInit(0.U(dataWidth.W))
  io.channels.write_data_channel.WDATA := write_data
  val write_strobe = RegInit(VecInit(Seq.fill(Parameters.WordSize)(false.B)))
  io.channels.write_data_channel.WSTRB := write_strobe.asUInt

  val AWVALID = RegInit(false.B)
  io.channels.write_address_channel.AWVALID := AWVALID
  val WVALID = RegInit(false.B)
  io.channels.write_data_channel.WVALID := WVALID
  val BREADY = RegInit(false.B)
  io.channels.write_response_channel.BREADY := BREADY

  io.channels.write_address_channel.AWPROT := 0.U
  

  // lab4(bus): master
  switch(state) {
    is (AXI4LiteStates.Idle) {
      
    }

    is (AXI4LiteStates.ReadAddr) {
      
    }

    is (AXI4LiteStates.ReadData) {
      
    }

    is (AXI4LiteStates.WriteAddr) {
      
    }

    is (AXI4LiteStates.WriteData) {
      
    }

    is (AXI4LiteStates.WriteResp) {
      
    }

    // lab4(bus): master End
  }

}


