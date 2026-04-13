// Generic scratchpad SRAM module — used for L0A, L0B, L0C, L1, UB
package davinci.common

import chisel3._
import chisel3.util._

class ScratchpadIO(val depth: Int, val widthBits: Int) extends Bundle {
  val read  = new Bundle {
    val en   = Input(Bool())
    val addr = Input(UInt(log2Ceil(depth).W))
    val data = Output(UInt(widthBits.W))
  }
  val write = new Bundle {
    val en   = Input(Bool())
    val addr = Input(UInt(log2Ceil(depth).W))
    val data = Input(UInt(widthBits.W))
  }
}

// Simple single-port-read, single-port-write scratchpad
class Scratchpad(val depth: Int, val widthBits: Int) extends Module {
  val io = IO(new ScratchpadIO(depth, widthBits))

  val mem = SyncReadMem(depth, UInt(widthBits.W))

  io.read.data := DontCare
  when(io.read.en) {
    io.read.data := mem.read(io.read.addr)
  }
  when(io.write.en) {
    mem.write(io.write.addr, io.write.data)
  }
}

// Banked scratchpad (for UB: 64 banks, 16 groups)
class BankedScratchpadIO(val nBanks: Int, val bankDepth: Int, val widthBits: Int) extends Bundle {
  val read = new Bundle {
    val en   = Input(Bool())
    val addr = Input(UInt(log2Ceil(nBanks * bankDepth).W))
    val data = Output(UInt(widthBits.W))
  }
  val write = new Bundle {
    val en   = Input(Bool())
    val addr = Input(UInt(log2Ceil(nBanks * bankDepth).W))
    val data = Input(UInt(widthBits.W))
  }
}

class BankedScratchpad(val nBanks: Int, val bankDepth: Int, val widthBits: Int) extends Module {
  val io = IO(new BankedScratchpadIO(nBanks, bankDepth, widthBits))

  val banks = Seq.fill(nBanks)(SyncReadMem(bankDepth, UInt(widthBits.W)))

  val bankBits = log2Ceil(nBanks)
  val bankSel  = io.read.addr(bankBits - 1, 0)
  val bankAddr = io.read.addr >> bankBits

  val wBankSel  = io.write.addr(bankBits - 1, 0)
  val wBankAddr = io.write.addr >> bankBits

  // Read
  val readResults = VecInit(banks.zipWithIndex.map { case (bank, i) =>
    bank.read(bankAddr, io.read.en && bankSel === i.U)
  })
  io.read.data := readResults(RegNext(bankSel))

  // Write
  banks.zipWithIndex.foreach { case (bank, i) =>
    when(io.write.en && wBankSel === i.U) {
      bank.write(wBankAddr, io.write.data)
    }
  }
}
