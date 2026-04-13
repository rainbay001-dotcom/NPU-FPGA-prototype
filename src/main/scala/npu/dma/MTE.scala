// MTE — Memory Transfer Engine (DMA)
// Handles explicit data movement between all memory levels:
//   HBM ↔ L2 ↔ L1 ↔ L0A/L0B/UB
//
// All memory except L2 is software-managed scratchpad.
// The compiler explicitly issues DMA commands via MTE.
// MTE goes through SMMU/TLB for VA→PA translation on HBM accesses.
//
// Key feature: double-buffering support — while CUBE processes tile[i],
// MTE prefetches tile[i+1] into the alternate L1 region.
package npu.dma

import chisel3._
import chisel3.util._
import npu.common._

class DMACommand extends Bundle {
  val opcode  = UInt(8.W)
  val srcAddr = UInt(40.W)  // source address (in source address space)
  val dstAddr = UInt(40.W)  // destination address
  val length  = UInt(20.W)  // transfer size in 256-bit words
  val nd2nz   = Bool()       // convert ND→NZ format on the fly
  val nz2nd   = Bool()       // convert NZ→ND format on the fly
}

class DMAStatus extends Bundle {
  val busy     = Bool()
  val done     = Bool()
  val error    = Bool()
  val wordsLeft = UInt(20.W)
}

// AXI4-like memory port (simplified)
class MemPortIO(val addrWidth: Int, val dataWidth: Int) extends Bundle {
  val ar = Decoupled(new Bundle {
    val addr = UInt(addrWidth.W)
    val len  = UInt(8.W)
  })
  val r = Flipped(Decoupled(new Bundle {
    val data = UInt(dataWidth.W)
    val last = Bool()
  }))
  val aw = Decoupled(new Bundle {
    val addr = UInt(addrWidth.W)
    val len  = UInt(8.W)
  })
  val w = Decoupled(new Bundle {
    val data = UInt(dataWidth.W)
    val last = Bool()
  })
}

class MTEIO(val p: NPUClusterParams) extends Bundle {
  val cmd = Flipped(Decoupled(new DMACommand))
  val status = Output(new DMAStatus)

  // Internal scratchpad ports — MTE drives these (Flipped: MTE is master)
  val l1   = Flipped(new ScratchpadIO(p.buffers.l1Size / 32, 256))
  val l0a  = Flipped(new ScratchpadIO(p.buffers.l0aSize / 32, 256))
  val l0b  = Flipped(new ScratchpadIO(p.buffers.l0bSize / 32, 256))

  // L2 cache port
  val l2   = Flipped(new ScratchpadIO(p.l2SizeKB * 1024 / 32, 256))

  // External memory port (AXI4-like, to DDR/HBM)
  val mem  = new MemPortIO(p.addrWidth, p.dataWidth)
}

class MTE(val p: NPUClusterParams) extends Module {
  val io = IO(new MTEIO(p))

  val sIdle :: sRead :: sWrite :: sDone :: Nil = Enum(4)
  val state = RegInit(sIdle)

  val cmdReg    = Reg(new DMACommand)
  val wordsDone = RegInit(0.U(20.W))
  val readBuf   = Reg(UInt(256.W))
  val readValid = RegInit(false.B)

  // Default: all ports inactive
  io.l1.read.en := false.B;  io.l1.read.addr := 0.U
  io.l1.write.en := false.B; io.l1.write.addr := 0.U; io.l1.write.data := 0.U
  io.l0a.read.en := false.B;  io.l0a.read.addr := 0.U
  io.l0a.write.en := false.B; io.l0a.write.addr := 0.U; io.l0a.write.data := 0.U
  io.l0b.read.en := false.B;  io.l0b.read.addr := 0.U
  io.l0b.write.en := false.B; io.l0b.write.addr := 0.U; io.l0b.write.data := 0.U
  io.l2.read.en := false.B;  io.l2.read.addr := 0.U
  io.l2.write.en := false.B; io.l2.write.addr := 0.U; io.l2.write.data := 0.U

  io.mem.ar.valid := false.B; io.mem.ar.bits.addr := 0.U; io.mem.ar.bits.len := 0.U
  io.mem.aw.valid := false.B; io.mem.aw.bits.addr := 0.U; io.mem.aw.bits.len := 0.U
  io.mem.w.valid  := false.B; io.mem.w.bits.data  := 0.U; io.mem.w.bits.last := false.B
  io.mem.r.ready  := false.B

  io.cmd.ready := state === sIdle
  io.status.busy := state =/= sIdle
  io.status.done := state === sDone
  io.status.error := false.B
  io.status.wordsLeft := cmdReg.length - wordsDone

  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
        cmdReg := io.cmd.bits
        wordsDone := 0.U
        readValid := false.B
        state := sRead
      }
    }

    is(sRead) {
      // Read from source based on opcode
      val op = cmdReg.opcode
      val srcAddr = cmdReg.srcAddr(19, 0) + wordsDone  // truncate to scratchpad range

      switch(op) {
        is(NPUConsts.Opcode.DMA_L2_TO_L1) {
          io.l2.read.en := true.B
          io.l2.read.addr := srcAddr
        }
        is(NPUConsts.Opcode.DMA_L1_TO_L0A) {
          io.l1.read.en := true.B
          io.l1.read.addr := srcAddr
        }
        is(NPUConsts.Opcode.DMA_L1_TO_L0B) {
          io.l1.read.en := true.B
          io.l1.read.addr := srcAddr
        }
        is(NPUConsts.Opcode.DMA_L1_TO_UB) {
          io.l1.read.en := true.B
          io.l1.read.addr := srcAddr
        }
        is(NPUConsts.Opcode.DMA_HBM_TO_L2) {
          // External memory read via AXI
          io.mem.ar.valid := true.B
          io.mem.ar.bits.addr := cmdReg.srcAddr + (wordsDone << 5)  // byte address
          io.mem.ar.bits.len := 0.U  // single beat
          io.mem.r.ready := true.B
        }
      }

      // One-cycle read latency for scratchpads
      readValid := true.B
      state := sWrite
    }

    is(sWrite) {
      val op = cmdReg.opcode
      val dstAddr = cmdReg.dstAddr(19, 0) + wordsDone
      val writeData = Wire(UInt(256.W))
      writeData := 0.U

      // Capture read data from previous cycle
      switch(op) {
        is(NPUConsts.Opcode.DMA_L2_TO_L1)  { writeData := io.l2.read.data }
        is(NPUConsts.Opcode.DMA_L1_TO_L0A)  { writeData := io.l1.read.data }
        is(NPUConsts.Opcode.DMA_L1_TO_L0B)  { writeData := io.l1.read.data }
        is(NPUConsts.Opcode.DMA_L1_TO_UB)   { writeData := io.l1.read.data }
        is(NPUConsts.Opcode.DMA_HBM_TO_L2) {
          when(io.mem.r.valid) { writeData := io.mem.r.bits.data }
        }
      }

      // Write to destination
      switch(op) {
        is(NPUConsts.Opcode.DMA_L2_TO_L1) {
          io.l1.write.en := true.B
          io.l1.write.addr := dstAddr
          io.l1.write.data := writeData
        }
        is(NPUConsts.Opcode.DMA_L1_TO_L0A) {
          io.l0a.write.en := true.B
          io.l0a.write.addr := dstAddr
          io.l0a.write.data := writeData
        }
        is(NPUConsts.Opcode.DMA_L1_TO_L0B) {
          io.l0b.write.en := true.B
          io.l0b.write.addr := dstAddr
          io.l0b.write.data := writeData
        }
        is(NPUConsts.Opcode.DMA_L1_TO_UB) {
          // UB write goes through VectorCore's external port (connected at cluster level)
        }
        is(NPUConsts.Opcode.DMA_HBM_TO_L2) {
          io.l2.write.en := true.B
          io.l2.write.addr := dstAddr
          io.l2.write.data := writeData
        }
      }

      wordsDone := wordsDone + 1.U
      when(wordsDone === cmdReg.length - 1.U) {
        state := sDone
      }.otherwise {
        state := sRead
      }
    }

    is(sDone) {
      state := sIdle  // auto-return to idle after 1 cycle
    }
  }
}
