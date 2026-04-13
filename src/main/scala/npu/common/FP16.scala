// IEEE 754 FP16 multiply-accumulate building blocks
// For FPGA prototype: use actual bit-level FP16 arithmetic
package npu.common

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

// FP16 + FP16 → FP16 adder (combinational, single cycle)
// Used for VECTOR core SIMD lanes
class FP16Add extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(16.W))
    val b   = Input(UInt(16.W))
    val out = Output(UInt(16.W))
  })

  val aSign = FP16Utils.fp16Sign(io.a)
  val bSign = FP16Utils.fp16Sign(io.b)
  val aExp  = FP16Utils.fp16Exp(io.a)
  val bExp  = FP16Utils.fp16Exp(io.b)
  val aMan  = FP16Utils.fp16Man(io.a)
  val bMan  = FP16Utils.fp16Man(io.b)

  val aIsZero = io.a(14, 0) === 0.U
  val bIsZero = io.b(14, 0) === 0.U

  // Implicit leading 1 for normalized numbers
  val aFull = Cat(Mux(aExp =/= 0.U, 1.U(1.W), 0.U(1.W)), aMan)  // 11 bits
  val bFull = Cat(Mux(bExp =/= 0.U, 1.U(1.W), 0.U(1.W)), bMan)  // 11 bits

  // Align exponents — work in 24-bit space for guard bits
  val expDiff  = (aExp.zext - bExp.zext).asSInt
  val aAligned = Wire(UInt(24.W))
  val bAligned = Wire(UInt(24.W))
  val rExp     = Wire(UInt(5.W))

  when(expDiff >= 0.S) {
    aAligned := aFull ## 0.U(13.W)
    bAligned := (bFull ## 0.U(13.W)) >> expDiff.asUInt
    rExp     := aExp
  }.otherwise {
    aAligned := (aFull ## 0.U(13.W)) >> ((-expDiff).asUInt)
    bAligned := bFull ## 0.U(13.W)
    rExp     := bExp
  }

  // Add or subtract based on signs
  val sameSign = aSign === bSign
  val sum = Wire(UInt(25.W))
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
  // After alignment, implicit 1 is at bit 23 of the 24-bit aligned values.
  // After addition, the 25-bit sum has leading 1 at bit 24 (carry) or bit 23 (normal) or lower.
  val lzRaw = PriorityEncoder(Reverse(sum))
  val needNorm = lzRaw > 1.U
  val normAmt  = lzRaw - 1.U
  val normShift = Mux(needNorm, Mux(normAmt > rExp, rExp, normAmt), 0.U)
  val normSum = (sum << normShift)(23, 0)
  val normExp = rExp - normShift

  // Extract 10 mantissa bits for FP16
  val finalMan = Mux(sum(24),
    sum(23, 14),     // carry: implicit 1 at bit 24, mantissa = bits 23..14
    normSum(22, 13)  // no carry: implicit 1 at bit 23, mantissa = bits 22..13
  )
  val finalExp = Mux(sum(24), rExp + 1.U, normExp)

  // Overflow → infinity
  val overflow = finalExp > 30.U
  // Pack result
  val packed = Cat(rSign, finalExp(4, 0), finalMan)
  val inf    = Cat(rSign, 0x1F.U(5.W), 0.U(10.W))

  io.out := Mux(aIsZero, io.b,
            Mux(bIsZero, io.a,
            Mux(sum === 0.U, 0.U(16.W),
            Mux(overflow, inf, packed))))
}

// FP16 × FP16 → FP16 multiplier (combinational, single cycle)
// Used for VECTOR core SIMD lanes (truncates product to FP16)
class FP16MulFP16 extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(16.W))
    val b   = Input(UInt(16.W))
    val out = Output(UInt(16.W))
  })

  val aSign = FP16Utils.fp16Sign(io.a)
  val bSign = FP16Utils.fp16Sign(io.b)
  val aExp  = FP16Utils.fp16Exp(io.a)
  val bExp  = FP16Utils.fp16Exp(io.b)
  val aMan  = FP16Utils.fp16Man(io.a)
  val bMan  = FP16Utils.fp16Man(io.b)

  val aIsZero = aExp === 0.U && aMan === 0.U
  val bIsZero = bExp === 0.U && bMan === 0.U

  val aFull = Cat(Mux(aExp =/= 0.U, 1.U(1.W), 0.U(1.W)), aMan)  // 11 bits
  val bFull = Cat(Mux(bExp =/= 0.U, 1.U(1.W), 0.U(1.W)), bMan)  // 11 bits

  val product = (aFull * bFull)(21, 0)  // 22 bits

  val rSign = aSign ^ bSign

  // Exponent: aExp + bExp - bias(15)
  val expSum = aExp +& bExp  // 6-bit
  val biasedExp = expSum -& 15.U(6.W)

  // Normalize
  val normalized = product(21)
  val rExp = Mux(normalized, biasedExp + 1.U, biasedExp)
  // Extract top 10 mantissa bits from 22-bit product
  val rMan = Mux(normalized, product(20, 11), product(19, 10))

  // Overflow → infinity, underflow → zero
  val overflow  = rExp > 30.U
  val underflow = biasedExp === 0.U || expSum < 15.U

  val packed = Cat(rSign, rExp(4, 0), rMan)
  val inf    = Cat(rSign, 0x1F.U(5.W), 0.U(10.W))

  io.out := Mux(aIsZero || bIsZero, 0.U(16.W),
            Mux(underflow, 0.U(16.W),
            Mux(overflow, inf, packed)))
}

// 16-wide FP16 SIMD add: operates on a 256-bit word (16 × FP16 lanes)
class FP16SIMDAdd extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(256.W))
    val b   = Input(UInt(256.W))
    val out = Output(UInt(256.W))
  })
  val lanes = (0 until 16).map { i =>
    val adder = Module(new FP16Add)
    adder.io.a := io.a(i * 16 + 15, i * 16)
    adder.io.b := io.b(i * 16 + 15, i * 16)
    adder.io.out
  }
  io.out := Cat(lanes.reverse)
}

// 16-wide FP16 SIMD mul: operates on a 256-bit word (16 × FP16 lanes)
class FP16SIMDMul extends Module {
  val io = IO(new Bundle {
    val a   = Input(UInt(256.W))
    val b   = Input(UInt(256.W))
    val out = Output(UInt(256.W))
  })
  val lanes = (0 until 16).map { i =>
    val mul = Module(new FP16MulFP16)
    mul.io.a := io.a(i * 16 + 15, i * 16)
    mul.io.b := io.b(i * 16 + 15, i * 16)
    mul.io.out
  }
  io.out := Cat(lanes.reverse)
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
  // After alignment, implicit 1 is at bit 47 of the 48-bit aligned values.
  // After addition, the 49-bit sum has the leading 1 at bit 48 (carry) or bit 47 (normal)
  // or lower (cancellation during subtraction).
  // PriorityEncoder(Reverse(sum)) counts from bit 48, so lz=0 means carry,
  // lz=1 means leading 1 at bit 47 (already normalized), lz>1 means needs left shift.
  val lzRaw = PriorityEncoder(Reverse(sum))
  // Shift needed to place leading 1 at bit 47: (lzRaw - 1) for non-carry cases
  val needNorm = lzRaw > 1.U
  val normAmt  = lzRaw - 1.U
  val normShift = Mux(needNorm, Mux(normAmt > rExp, rExp, normAmt), 0.U)
  val normSum = (sum << normShift)(47, 0)
  val normExp = rExp - normShift

  // Handle carry out from addition
  val finalMan = Mux(sum(48),
    sum(47, 25),     // carry: implicit 1 at bit 48, mantissa = bits 47..25
    normSum(46, 24)  // no carry: implicit 1 at bit 47, mantissa = bits 46..24
  )
  val finalExp = Mux(sum(48), rExp + 1.U, normExp)

  io.out := Mux(aIsZero, io.b,
            Mux(bIsZero, io.a,
            Mux(sum === 0.U, 0.U(32.W),
            FP16Utils.packFP32(rSign, finalExp, finalMan))))
}
