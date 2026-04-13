// NPU FPGA Prototype — Configuration Parameters
// Modeled after a split-architecture NPU with CUBE + VECTOR cores
package npu.common

import chisel3._
import chisel3.util._

// Per-core buffer sizes (bytes)
case class CoreBufferParams(
  l0aSize:  Int = 64 * 1024,   // 64 KB — CUBE input A (activations)
  l0bSize:  Int = 64 * 1024,   // 64 KB — CUBE input B (weights, NZ format)
  l0cSize:  Int = 128 * 1024,  // 128 KB — CUBE output accumulator
  l1Size:   Int = 512 * 1024,  // 512 KB — L1 scratchpad (double-buffer region)
  ubSize:   Int = 192 * 1024,  // 192 KB — VECTOR Unified Buffer
  ubBanks:  Int = 64,          // 64 UB banks
  ubGroups: Int = 16,          // 16 bank groups
  fbSizes:  Seq[Int] = Seq(2048, 1024, 2048, 2048), // Fixpipe buffers fb0-fb3 = 7 KB
  btSize:   Int = 1024         // Bias table: 1 KB
)

// CUBE MAC array dimensions — one tile per cycle
case class CubeTileParams(
  m: Int = 16,  // output rows
  k: Int = 16,  // reduction dimension (FP16); 32 for INT8, 64 for INT4
  n: Int = 16   // output cols
) {
  def macsPerCycle: Int = m * k * n  // 4096 for FP16
}

// Cluster-level parameters
case class NPUClusterParams(
  cubeCores:   Int = 1,           // FPGA prototype: 1-2
  vectorCores: Int = 2,           // FPGA prototype: 2-4
  l2SizeKB:    Int = 2 * 1024,    // 2 MB for FPGA
  l2Pages:     Int = 64,
  freqMHz:     Int = 200,         // FPGA target freq
  dataWidth:   Int = 256,         // AXI data width bits
  addrWidth:   Int = 40,          // address bits
  buffers:     CoreBufferParams  = CoreBufferParams(),
  cubeTile:    CubeTileParams    = CubeTileParams()
)

// Fixed-point representation for FP16 MAC: we use UInt(16.W) for FP16 bit patterns
// and UInt(32.W) for FP32 accumulators. Actual IEEE 754 logic is in the MAC unit.
object NPUConsts {
  val FP16_WIDTH  = 16
  val FP32_WIDTH  = 32
  val BF16_WIDTH  = 16
  val INT8_WIDTH  = 8
  val INT32_WIDTH = 32

  // NZ (Fractal Z) tile: 16x16 elements stored contiguously
  val NZ_TILE_ROWS = 16
  val NZ_TILE_COLS = 16

  // Instruction opcodes for the NPU command interface
  object Opcode {
    // CUBE
    val MMAD          = 0x01.U(8.W)  // matrix multiply-add

    // VECTOR ALU
    val VADD          = 0x10.U(8.W)
    val VSUB          = 0x11.U(8.W)
    val VMUL          = 0x12.U(8.W)
    val VDIV          = 0x13.U(8.W)
    val VEXP          = 0x14.U(8.W)
    val VLN           = 0x15.U(8.W)
    val VSQRT         = 0x16.U(8.W)
    val VRELU         = 0x17.U(8.W)
    val VABS          = 0x18.U(8.W)
    val VMAX          = 0x19.U(8.W)
    val VMIN          = 0x1A.U(8.W)
    val VCONV         = 0x1B.U(8.W)
    val VREDUCE_SUM   = 0x1C.U(8.W)
    val VREDUCE_MAX   = 0x1D.U(8.W)
    val VMULS         = 0x1E.U(8.W)  // multiply scalar

    // DMA / MTE
    val DMA_HBM_TO_L2 = 0x30.U(8.W)
    val DMA_L2_TO_L1  = 0x31.U(8.W)
    val DMA_L1_TO_L0A = 0x32.U(8.W)
    val DMA_L1_TO_L0B = 0x33.U(8.W)
    val DMA_L0C_TO_L1 = 0x34.U(8.W)
    val DMA_L1_TO_UB  = 0x35.U(8.W)
    val DMA_UB_TO_L2  = 0x36.U(8.W)
    val DMA_L2_TO_HBM = 0x37.U(8.W)

    // Fixpipe
    val FIXPIPE_L0C_TO_L1  = 0x40.U(8.W)
    val FIXPIPE_L0C_TO_UB  = 0x41.U(8.W)

    // Control
    val NOP           = 0x00.U(8.W)
    val BARRIER       = 0xFF.U(8.W)
  }
}
