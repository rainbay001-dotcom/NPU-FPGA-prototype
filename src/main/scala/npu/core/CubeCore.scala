// CUBE Core — 16×16×16 MAC array with L0A/L0B/L0C scratchpads and L1 buffer
// Models one CUBE core in a split-architecture NPU
//
// Dataflow per mmad:
//   1. MTE loads tiles from HBM → L2 → L1 (double-buffered)
//   2. L1 → L0A (activations) and L1 → L0B (weights/NZ format)
//   3. CUBE MAC: C[m][n] += A[m][k] * B[k][n] — one 16×16×16 tile per cycle
//   4. Results accumulate in L0C across K tiles
//   5. L0C → Fixpipe → L1 or UB
package npu.core

import chisel3._
import chisel3.util._
import npu.common._

class CubeCoreIO(val p: NPUClusterParams) extends Bundle {
  // Command interface (from Task Scheduler)
  val cmd = Flipped(Decoupled(new CubeCommand(p)))

  // Scratchpad DMA ports (connected to MTE DMA for loading tiles)
  val l1  = new ScratchpadIO(p.buffers.l1Size / 32, 256)
  val l0a = new ScratchpadIO(p.buffers.l0aSize / 32, 256)
  val l0b = new ScratchpadIO(p.buffers.l0bSize / 32, 256)

  // Output: L0C drain to Fixpipe
  val l0c_out = Decoupled(new CubeTileOutput(p))

  val busy = Output(Bool())
}

class CubeCommand(val p: NPUClusterParams) extends Bundle {
  val opcode   = UInt(8.W)
  val l0a_addr = UInt(16.W)  // base address in L0A for tile A
  val l0b_addr = UInt(16.W)  // base address in L0B for tile B
  val l0c_addr = UInt(16.W)  // base address in L0C for accumulator
  val k_tiles  = UInt(16.W)  // number of K-dimension tiles to accumulate
  val accum    = Bool()       // true = accumulate into existing L0C, false = overwrite
}

class CubeTileOutput(val p: NPUClusterParams) extends Bundle {
  // One 16×16 tile of FP32 results = 16*16*4 = 1024 bytes
  // Output as a stream of 256-bit words: 1024/32 = 32 words
  val data = UInt(256.W)
  val last = Bool()  // last word of tile
}

class CubeCore(val p: NPUClusterParams) extends Module {
  val io = IO(new CubeCoreIO(p))

  val M = p.cubeTile.m  // 16
  val K = p.cubeTile.k  // 16
  val N = p.cubeTile.n  // 16

  // ---- Scratchpad memories ----
  // L0A: 64 KB, addressed as 256-bit (32-byte) words = 2048 entries
  val l0a = SyncReadMem(p.buffers.l0aSize / 32, UInt(256.W))
  // L0B: 64 KB
  val l0b = SyncReadMem(p.buffers.l0bSize / 32, UInt(256.W))
  // L0C: 128 KB, stores FP32 accumulators, 32-byte words = 4096 entries
  val l0c = SyncReadMem(p.buffers.l0cSize / 32, UInt(256.W))
  // L1: 512 KB, internal SRAM with external DMA port (io.l1)
  val l1Depth = p.buffers.l1Size / 32
  val l1mem = SyncReadMem(l1Depth, UInt(256.W))

  // Connect external DMA ports to internal SRAMs
  io.l1.read.data := l1mem.read(io.l1.read.addr, io.l1.read.en)
  when(io.l1.write.en) { l1mem.write(io.l1.write.addr, io.l1.write.data) }

  io.l0a.read.data := l0a.read(io.l0a.read.addr, io.l0a.read.en)
  when(io.l0a.write.en) { l0a.write(io.l0a.write.addr, io.l0a.write.data) }

  io.l0b.read.data := l0b.read(io.l0b.read.addr, io.l0b.read.en)
  when(io.l0b.write.en) { l0b.write(io.l0b.write.addr, io.l0b.write.data) }

  // ---- MAC Array: 16×16 FP16×FP16→FP32 multipliers + FP32 accumulators ----
  // Each cycle processes one "slice": A[m, k_i] × B[k_i, n] for all m,n
  // A tile has M*K = 256 FP16 values = 512 bytes = 16 × 256-bit words
  // B tile has K*N = 256 FP16 values = 512 bytes = 16 × 256-bit words

  // MAC units: M×N = 256 multipliers, each doing FP16×FP16→FP32
  val muls = Seq.fill(M)(Seq.fill(N)(Module(new FP16Mul)))
  val accs = Seq.fill(M)(Seq.fill(N)(Module(new FP32Add)))

  // Accumulator registers: M×N FP32 values
  val accumRegs = RegInit(VecInit(Seq.fill(M)(VecInit(Seq.fill(N)(0.U(32.W))))))

  // ---- State machine ----
  val sIdle :: sLoadA :: sLoadB :: sCompute :: sDrain :: Nil = Enum(5)
  val state = RegInit(sIdle)

  val cmdReg     = Reg(new CubeCommand(p))
  val kCount     = RegInit(0.U(16.W))  // which K slice we're on
  val wordCount  = RegInit(0.U(8.W))   // word counter within a tile load
  val drainCount = RegInit(0.U(8.W))   // word counter for draining L0C

  // A tile row storage: 16 FP16 values per row = 32 bytes = 1 × 256-bit word
  // We read one row per cycle from L0A/L0B
  val aRow = RegInit(VecInit(Seq.fill(M)(0.U(256.W))))  // M rows of A, each 256 bits (16 × FP16)
  val bRow = RegInit(VecInit(Seq.fill(K)(0.U(256.W))))  // K rows of B, each 256 bits (16 × FP16)

  io.cmd.ready   := state === sIdle
  io.l0c_out.valid := state === sDrain
  io.l0c_out.bits.data := 0.U
  io.l0c_out.bits.last := false.B
  io.busy := state =/= sIdle

  // L1 port is driven by MTE externally — CubeCore doesn't drive it.
  // The L1 scratchpad is physically inside CubeCore but MTE has DMA access.

  // Wire up MAC array (active during sCompute)
  // Architecture: each "compute" cycle processes one k-slice across all m,n.
  // aRow(m) = 256-bit word containing 16 FP16 values: a[m][0..15]
  // bRow(k) = 256-bit word containing 16 FP16 values: b[k][0..15]
  // We iterate kCount from 0..15, extracting a[m][kCount] and b[kCount][n].
  //
  // Dynamic bit selection: shift right by (kCount * 16) then mask lower 16 bits.
  for (m <- 0 until M) {
    for (n <- 0 until N) {
      val aShifted = (aRow(m) >> (kCount(3, 0) ## 0.U(4.W)))  // shift by kCount*16
      val aElem = aShifted(15, 0)

      // bRow is indexed by kCount — need dynamic Vec indexing
      val bWord = bRow(kCount(3, 0))
      val bElem = bWord(n * 16 + 15, n * 16)  // n is a Scala Int, so static select

      muls(m)(n).io.a := aElem
      muls(m)(n).io.b := bElem
      accs(m)(n).io.a := muls(m)(n).io.out
      accs(m)(n).io.b := accumRegs(m)(n)
    }
  }

  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
        cmdReg := io.cmd.bits
        kCount := 0.U
        wordCount := 0.U
        when(!io.cmd.bits.accum) {
          // Clear accumulators
          for (m <- 0 until M; n <- 0 until N) {
            accumRegs(m)(n) := 0.U
          }
        }
        state := sLoadA
      }
    }

    is(sLoadA) {
      // Read M words from L0A (one row per cycle)
      val addr = cmdReg.l0a_addr + wordCount
      val readData = l0a.read(addr)
      when(wordCount > 0.U) {
        aRow(wordCount - 1.U) := RegNext(readData)
      }
      wordCount := wordCount + 1.U
      when(wordCount === M.U) {
        aRow((M - 1).U) := readData
        wordCount := 0.U
        state := sLoadB
      }
    }

    is(sLoadB) {
      // Read K words from L0B
      val addr = cmdReg.l0b_addr + wordCount
      val readData = l0b.read(addr)
      when(wordCount > 0.U) {
        bRow(wordCount - 1.U) := RegNext(readData)
      }
      wordCount := wordCount + 1.U
      when(wordCount === K.U) {
        bRow((K - 1).U) := readData
        wordCount := 0.U
        kCount := 0.U
        state := sCompute
      }
    }

    is(sCompute) {
      // Each cycle: multiply a[m][kCount] × b[kCount][n] and accumulate
      for (m <- 0 until M; n <- 0 until N) {
        accumRegs(m)(n) := accs(m)(n).io.out
      }
      kCount := kCount + 1.U
      when(kCount === (K - 1).U) {
        drainCount := 0.U
        state := sDrain
      }
    }

    is(sDrain) {
      // Output accumulated results: 16×16 FP32 = 256 values
      // Each 256-bit word holds 8 FP32 values → 32 words total
      val wordIdx = drainCount
      val row = wordIdx >> 1  // 2 words per row (16 FP32 = 512 bits = 2 × 256)
      val half = wordIdx(0)
      val outData = Wire(UInt(256.W))
      outData := Cat(
        (0 until 8).reverse.map { i =>
          val col = Mux(half, (i + 8).U, i.U)
          accumRegs(row)(col)
        }
      )

      io.l0c_out.bits.data := outData
      io.l0c_out.bits.last := drainCount === 31.U

      when(io.l0c_out.ready) {
        drainCount := drainCount + 1.U
        when(drainCount === 31.U) {
          state := sIdle
        }
      }
    }
  }
}
