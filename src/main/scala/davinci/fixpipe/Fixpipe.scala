// Fixpipe — Fixed-function post-processing pipeline
// Sits between CUBE L0C output and L1/UB destinations
//
// Pipeline stages (910C):
//   1. Dequant:  INT32 → FP16/FP32
//   2. Bias Add: add per-channel bias from fb0-fb3 buffers (7 KB total)
//   3. Requant:  FP16/FP32 → INT8
//   4. ReLU:     max(0, x) activation
//   5. NZ→ND:   Fractal Z to row-major format conversion
//
// Each stage can be independently enabled/disabled per command.
package davinci.fixpipe

import chisel3._
import chisel3.util._
import davinci.common._
import davinci.core.CubeTileOutput

class FixpipeConfig extends Bundle {
  val enableDequant  = Bool()
  val enableBias     = Bool()
  val enableRequant  = Bool()
  val enableRelu     = Bool()
  val enableNZ2ND    = Bool()
  val biasAddr       = UInt(16.W)  // address in fb buffers
  val outputToUB     = Bool()       // true = output to VECTOR UB, false = output to L1
}

class FixpipeIO(val p: NPUClusterParams) extends Bundle {
  val config = Input(new FixpipeConfig)

  // Input from CUBE L0C drain
  val in = Flipped(Decoupled(new CubeTileOutput(p)))

  // Output: 256-bit words to L1 or UB
  val out = Decoupled(new Bundle {
    val data  = UInt(256.W)
    val last  = Bool()
    val toUB  = Bool()  // routing hint
  })
}

class Fixpipe(val p: NPUClusterParams) extends Module {
  val io = IO(new FixpipeIO(p))

  // Fixpipe bias buffers: fb0(2KB) + fb1(1KB) + fb2(2KB) + fb3(2KB) = 7 KB
  // Addressed as 256-bit words
  val fb = SyncReadMem(p.buffers.fbSizes.sum / 32, UInt(256.W))

  // Pipeline registers (one stage per clock)
  val s1_valid = RegInit(false.B)
  val s1_data  = Reg(UInt(256.W))
  val s1_last  = Reg(Bool())

  val s2_valid = RegInit(false.B)
  val s2_data  = Reg(UInt(256.W))
  val s2_last  = Reg(Bool())

  val s3_valid = RegInit(false.B)
  val s3_data  = Reg(UInt(256.W))
  val s3_last  = Reg(Bool())

  val wordCount = RegInit(0.U(8.W))

  // ---- Stage 1: Dequant (INT32→FP16) or passthrough ----
  io.in.ready := !s1_valid || (s1_valid && !s2_valid)

  when(io.in.fire) {
    s1_valid := true.B
    s1_last  := io.in.bits.last
    when(io.config.enableDequant) {
      // Simplified dequant: treat input as 8 × INT32, convert to 8 × FP32
      // Real hardware does proper fixed-point → float conversion
      s1_data := io.in.bits.data  // passthrough for now; real impl converts
    }.otherwise {
      s1_data := io.in.bits.data
    }
  }.elsewhen(s1_valid && !s2_valid) {
    s1_valid := false.B
  }

  // ---- Stage 2: Bias Add ----
  when(s1_valid && !s2_valid) {
    s2_valid := true.B
    s2_last  := s1_last
    when(io.config.enableBias) {
      val biasData = fb.read(io.config.biasAddr + wordCount)
      // Add bias word-by-word (8 × FP32 lanes)
      // Simplified: XOR-based placeholder — real hardware uses FP32 adders
      s2_data := s1_data  // TODO: actual FP32 vector add with bias
    }.otherwise {
      s2_data := s1_data
    }
    wordCount := wordCount + 1.U
  }.elsewhen(s2_valid && !s3_valid) {
    s2_valid := false.B
  }

  // ---- Stage 3: ReLU + NZ→ND ----
  when(s2_valid && !s3_valid) {
    s3_valid := true.B
    s3_last  := s2_last

    val afterRelu = Wire(UInt(256.W))
    when(io.config.enableRelu) {
      // FP32 ReLU: zero out negative values (check sign bit of each 32-bit lane)
      val lanes = VecInit((0 until 8).map { i =>
        val elem = s2_data(i * 32 + 31, i * 32)
        Mux(elem(31), 0.U(32.W), elem)
      })
      afterRelu := Cat(lanes.reverse)
    }.otherwise {
      afterRelu := s2_data
    }

    when(io.config.enableNZ2ND) {
      // NZ→ND: Fractal Z (16×16 contiguous tiles) → row-major
      // For a single tile output this is an address remapping, not data transform
      // The reordering happens at the write-address level, not the data level
      s3_data := afterRelu  // data is the same; address reorder handled externally
    }.otherwise {
      s3_data := afterRelu
    }
  }.elsewhen(s3_valid && io.out.ready) {
    s3_valid := false.B
  }

  // ---- Output ----
  io.out.valid     := s3_valid
  io.out.bits.data := s3_data
  io.out.bits.last := s3_last
  io.out.bits.toUB := io.config.outputToUB

  when(io.out.fire && io.out.bits.last) {
    wordCount := 0.U
  }
}
