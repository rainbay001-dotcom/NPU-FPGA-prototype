// IEEE 754 FP16 multiply-accumulate building blocks
// For FPGA prototype: use actual bit-level FP16 arithmetic
package davinci.common

import chisel3._
import chisel3.util._

// FP16 format: 1 sign + 5 exponent + 10 mantissa
// FP32 format: 1 sign + 8 exponent + 23 mantissa
//
// For the FPGA prototype we implement FP16×FP16→FP32 multiply and FP32+FP32→FP32 add.
// This is the core datapath of the CUBE mmad instruction.

object FP16Utils {
  def fp16Sign(x: UInt): Bool = x(15)
  def fp16Exp(x: UInt):  UInt = x(14, 10)
  def fp16Man(x: UInt):  UInt = x(9, 0)

  def fp32Sign(x: UInt): Bool = x(31)
  def fp32Exp(x: UInt):  UInt = x(30, 23)
  def fp32Man(x: UInt):  UInt = x(22, 0)

  // Pack FP32 from components
  def packFP32(sign: Bool, exp: UInt, man: UInt): UInt =
    Cat(sign, exp(7, 0), man(22, 0))
}

// Single FP16 × FP16 → FP32 multiplier (combinational, single cycle)
class FP16Mul extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(16.W))
    val b   = Input(UInt(16.W))
    val out = Output(UInt(32.W))
  })

  val aSign = FP16Utils.fp16Sign(io.a)
  val bSign = FP16Utils.fp16Sign(io.b)
  val aExp  = FP16Utils.fp16Exp(io.a)
  val bExp  = FP16Utils.fp16Exp(io.b)
  val aMan  = FP16Utils.fp16Man(io.a)
  val bMan  = FP16Utils.fp16Man(io.b)

  val aIsZero = aExp === 0.U && aMan === 0.U
  val bIsZero = bExp === 0.U && bMan === 0.U

  // Implicit leading 1 for normalized numbers
  val aFull = Cat(Mux(aExp =/= 0.U, 1.U(1.W), 0.U(1.W)), aMan)  // 11 bits
  val bFull = Cat(Mux(bExp =/= 0.U, 1.U(1.W), 0.U(1.W)), bMan)  // 11 bits

  // Product: 22 bits (11 × 11)
  val product = (aFull * bFull)(21, 0)

  // Result sign
  val rSign = aSign ^ bSign

  // Result exponent: aExp + bExp - bias(15) + bias_fp32(127) - bias_fp16(15) = aExp + bExp + 97
  // FP16 bias = 15, FP32 bias = 127
  // Combined: (aExp - 15) + (bExp - 15) + 127 = aExp + bExp + 97
  val expSum = aExp +& bExp +& 97.U(9.W)  // 9-bit to handle overflow

  // Normalize: product is 1x.xxxx... (bit 21) or 0x.xxxx... (bit 20)
  val normalized = product(21)
  val rExp = Mux(normalized, expSum + 1.U, expSum)
  // Extract 23 mantissa bits for FP32 from 22-bit product
  val rMan = Mux(normalized,
    product(20, 0) ## 0.U(2.W),  // shift left to fill 23 bits
    product(19, 0) ## 0.U(3.W)
  )

  val result = Mux(aIsZero || bIsZero,
    0.U(32.W),
    FP16Utils.packFP32(rSign, rExp(7, 0), rMan(22, 0))
  )

  io.out := result
}

// FP32 + FP32 → FP32 adder (combinational, simplified — no full rounding)
// Used for accumulation in CUBE L0C
class FP32Add extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  val aSign = FP16Utils.fp32Sign(io.a)
  val bSign = FP16Utils.fp32Sign(io.b)
  val aExp  = FP16Utils.fp32Exp(io.a)
  val bExp  = FP16Utils.fp32Exp(io.b)
  val aMan  = Cat(1.U(1.W), FP16Utils.fp32Man(io.a))  // 24 bits with implicit 1
  val bMan  = Cat(1.U(1.W), FP16Utils.fp32Man(io.b))  // 24 bits

  val aIsZero = io.a(30, 0) === 0.U
  val bIsZero = io.b(30, 0) === 0.U

  // Align exponents
  val expDiff  = (aExp.zext - bExp.zext).asSInt
  val aAligned = Wire(UInt(48.W))
  val bAligned = Wire(UInt(48.W))
  val rExp     = Wire(UInt(8.W))

  when(expDiff >= 0.S) {
    aAligned := aMan ## 0.U(24.W)
    bAligned := (bMan ## 0.U(24.W)) >> expDiff.asUInt
    rExp     := aExp
  }.otherwise {
    aAligned := (aMan ## 0.U(24.W)) >> ((-expDiff).asUInt)
    bAligned := bMan ## 0.U(24.W)
    rExp     := bExp
  }

  // Add or subtract based on signs
  val sameSign = aSign === bSign
  val sum = Wire(UInt(49.W))
  val rSign = Wire(Bool())

  when(sameSign) {
    sum   := aAligned +& bAligned
    rSign := aSign
  }.otherwise {
    when(aAligned >= bAligned) {
      sum   := aAligned - bAligned
      rSign := aSign
    }.otherwise {
      sum   := bAligned - aAligned
      rSign := bSign
    }
  }

  // Normalize: find leading 1
  val lz = PriorityEncoder(Reverse(sum))
  val normShift = Mux(lz > rExp, rExp, lz)  // clamp to avoid underflow
  val normSum = (sum << normShift)(47, 0)
  val normExp = rExp - normShift + Mux(sum(48), 1.U, 0.U)

  // Handle carry out from addition
  val finalMan = Mux(sum(48),
    sum(47, 25),  // shift right, take top 23 bits
    normSum(47, 25)
  )
  val finalExp = Mux(sum(48), rExp + 1.U, normExp)

  io.out := Mux(aIsZero, io.b,
            Mux(bIsZero, io.a,
            Mux(sum === 0.U, 0.U(32.W),
            FP16Utils.packFP32(rSign, finalExp, finalMan))))
}
