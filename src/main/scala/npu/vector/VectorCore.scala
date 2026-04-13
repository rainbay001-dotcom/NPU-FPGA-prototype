// VECTOR Core — Element-wise ALU with 192 KB Unified Buffer (UB)
// Models one VECTOR core in a split-architecture NPU
//
// The VECTOR core operates independently from CUBE cores (split architecture).
// It reads/writes UB and executes vector ALU instructions: vadd, vmul, vexp, vrelu, etc.
// UB receives data either from L1 (via MTE) or from Fixpipe (CUBE → Fixpipe → UB path).
package npu.vector

import chisel3._
import chisel3.util._
import npu.common._

class VectorCommand extends Bundle {
  val opcode  = UInt(8.W)
  val src0    = UInt(16.W)  // UB address for source operand 0
  val src1    = UInt(16.W)  // UB address for source operand 1 (or scalar immediate)
  val dst     = UInt(16.W)  // UB address for result
  val len     = UInt(16.W)  // number of elements to process
  val scalar  = UInt(32.W)  // scalar operand for vmuls, vadds, etc.
  val useScalar = Bool()
}

class VectorCoreIO(val p: NPUClusterParams) extends Bundle {
  val cmd  = Flipped(Decoupled(new VectorCommand))
  // UB external port for MTE/Fixpipe to load/store data
  val ub_ext = new BankedScratchpadIO(p.buffers.ubBanks, p.buffers.ubSize / (p.buffers.ubBanks * 32), 256)
  val busy = Output(Bool())
}

class VectorCore(val p: NPUClusterParams) extends Module {
  val io = IO(new VectorCoreIO(p))

  val UB_DEPTH = p.buffers.ubSize / 32  // 256-bit words
  val ub = Module(new BankedScratchpad(p.buffers.ubBanks, UB_DEPTH / p.buffers.ubBanks, 256))

  // State machine
  val sIdle :: sExecute :: Nil = Enum(2)
  val state = RegInit(sIdle)

  val cmdReg = Reg(new VectorCommand)
  val elemIdx = RegInit(0.U(16.W))

  // 256 bits = 16 FP16 or 8 FP32 elements per word
  // For simplicity, operate on 256-bit words (vector width)
  val wordsToProcess = Wire(UInt(16.W))
  wordsToProcess := (cmdReg.len + 15.U) >> 4  // ceil(len / 16) for FP16

  io.cmd.ready := state === sIdle
  io.busy := state =/= sIdle

  // UB arbitration: internal ALU vs external port
  val useInternal = state === sExecute
  val src0Data = Wire(UInt(256.W))
  val src1Data = Wire(UInt(256.W))

  // Internal pipeline: read src0, read src1, capture src1, compute, write
  val sRead0 :: sRead1 :: sCapture :: sCalc :: sWrite :: Nil = Enum(5)
  val phase = RegInit(sRead0)

  val src0Reg = Reg(UInt(256.W))
  val src1Reg = Reg(UInt(256.W))
  val resultReg = Reg(UInt(256.W))

  // Connect UB ports
  ub.io.read.en   := false.B
  ub.io.read.addr  := 0.U
  ub.io.write.en   := false.B
  ub.io.write.addr  := 0.U
  ub.io.write.data  := 0.U

  // External UB port passthrough when idle
  when(!useInternal) {
    ub.io.read.en   := io.ub_ext.read.en
    ub.io.read.addr  := io.ub_ext.read.addr
    ub.io.write.en   := io.ub_ext.write.en
    ub.io.write.addr  := io.ub_ext.write.addr
    ub.io.write.data  := io.ub_ext.write.data
  }
  io.ub_ext.read.data := ub.io.read.data

  src0Data := ub.io.read.data
  src1Data := ub.io.read.data

  // ---- ALU: 16-wide FP16 SIMD lanes ----
  val simdAdd = Module(new FP16SIMDAdd)
  val simdMul = Module(new FP16SIMDMul)

  simdAdd.io.a := src0Reg
  simdAdd.io.b := src1Reg
  simdMul.io.a := src0Reg
  simdMul.io.b := src1Reg

  def fp16Relu(a: UInt): UInt = {
    val lanes = VecInit((0 until 16).map { i =>
      val elem = a(i * 16 + 15, i * 16)
      Mux(elem(15), 0.U(16.W), elem)
    })
    Cat(lanes.reverse)
  }

  // ALU operation select
  val aluResult = Wire(UInt(256.W))
  aluResult := 0.U
  switch(cmdReg.opcode) {
    is(NPUConsts.Opcode.VADD)  { aluResult := simdAdd.io.out }
    is(NPUConsts.Opcode.VMUL)  { aluResult := simdMul.io.out }
    is(NPUConsts.Opcode.VRELU) { aluResult := fp16Relu(src0Reg) }
  }

  switch(state) {
    is(sIdle) {
      when(io.cmd.valid) {
        cmdReg := io.cmd.bits
        elemIdx := 0.U
        phase := sRead0
        state := sExecute
      }
    }

    is(sExecute) {
      switch(phase) {
        is(sRead0) {
          ub.io.read.en := true.B
          ub.io.read.addr := cmdReg.src0 + elemIdx
          phase := sRead1
        }
        is(sRead1) {
          src0Reg := ub.io.read.data
          ub.io.read.en := true.B
          ub.io.read.addr := cmdReg.src1 + elemIdx
          phase := sCapture
        }
        is(sCapture) {
          src1Reg := ub.io.read.data
          phase := sCalc
        }
        is(sCalc) {
          resultReg := aluResult
          phase := sWrite
        }
        is(sWrite) {
          ub.io.write.en := true.B
          ub.io.write.addr := cmdReg.dst + elemIdx
          ub.io.write.data := resultReg
          elemIdx := elemIdx + 1.U
          when(elemIdx === wordsToProcess - 1.U) {
            state := sIdle
          }.otherwise {
            phase := sRead0
          }
        }
      }
    }
  }
}
