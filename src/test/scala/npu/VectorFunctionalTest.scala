// Functional test: VectorCore SIMD operations (VADD, VMUL, VRELU)
package npu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import npu.common._
import npu.vector._

class VectorFunctionalTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  val p = NPUClusterParams(cubeCores = 1, vectorCores = 2, l2SizeKB = 64)

  // IEEE 754 FP16 encoding helpers
  def fp16(value: Float): Int = {
    if (value == 0.0f) return 0
    val bits = java.lang.Float.floatToIntBits(value)
    val sign = (bits >> 31) & 1
    val exp  = ((bits >> 23) & 0xFF) - 127 + 15
    val man  = (bits >> 13) & 0x3FF
    if (exp <= 0) return (sign << 15)
    if (exp >= 31) return (sign << 15) | 0x7C00
    (sign << 15) | (exp << 10) | man
  }

  def fp16ToFloat(bits: Int): Float = {
    val sign = (bits >> 15) & 1
    val exp  = (bits >> 10) & 0x1F
    val man  = bits & 0x3FF
    if (exp == 0 && man == 0) return if (sign == 1) -0.0f else 0.0f
    if (exp == 0x1F) return if (sign == 1) Float.NegativeInfinity else Float.PositiveInfinity
    val fexp = exp - 15 + 127
    val fbits = (sign << 31) | (fexp << 23) | (man << 13)
    java.lang.Float.intBitsToFloat(fbits)
  }

  def packRow(values: Seq[Float]): BigInt = {
    require(values.length == 16)
    values.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(fp16(v) & 0xFFFF) << (i * 16))
    }
  }

  def extractFP16(word: BigInt, index: Int): Float = {
    val bits = ((word >> (index * 16)) & BigInt(0xFFFF)).toInt
    fp16ToFloat(bits)
  }

  def initVectorCore(c: VectorCore): Unit = {
    c.io.cmd.valid.poke(false.B)
    c.io.ub_ext.read.en.poke(false.B)
    c.io.ub_ext.read.addr.poke(0.U)
    c.io.ub_ext.write.en.poke(false.B)
    c.io.ub_ext.write.addr.poke(0.U)
    c.io.ub_ext.write.data.poke(0.U)
    c.clock.step(2)
  }

  def writeUB(c: VectorCore, addr: Int, data: BigInt): Unit = {
    c.io.ub_ext.write.en.poke(true.B)
    c.io.ub_ext.write.addr.poke(addr.U)
    c.io.ub_ext.write.data.poke(data.U)
    c.clock.step(1)
    c.io.ub_ext.write.en.poke(false.B)
  }

  def readUB(c: VectorCore, addr: Int): BigInt = {
    c.io.ub_ext.read.en.poke(true.B)
    c.io.ub_ext.read.addr.poke(addr.U)
    c.clock.step(1)  // SyncReadMem: 1-cycle latency
    c.io.ub_ext.read.en.poke(false.B)
    c.io.ub_ext.read.data.peek().litValue
  }

  def issueCmd(c: VectorCore, opcode: Int, src0: Int, src1: Int, dst: Int, len: Int): Unit = {
    c.io.cmd.valid.poke(true.B)
    c.io.cmd.bits.opcode.poke(opcode.U)
    c.io.cmd.bits.src0.poke(src0.U)
    c.io.cmd.bits.src1.poke(src1.U)
    c.io.cmd.bits.dst.poke(dst.U)
    c.io.cmd.bits.len.poke(len.U)
    c.io.cmd.bits.scalar.poke(0.U)
    c.io.cmd.bits.useScalar.poke(false.B)
    c.clock.step(1)
    c.io.cmd.valid.poke(false.B)
  }

  def waitDone(c: VectorCore, maxCycles: Int = 200): Int = {
    var cycles = 0
    while (c.io.busy.peek().litValue == 1 && cycles < maxCycles) {
      c.clock.step(1)
      cycles += 1
    }
    cycles
  }

  "VectorCore VADD" should "add two FP16 vectors element-wise" in {
    test(new VectorCore(p)) { c =>
      initVectorCore(c)

      val src0 = packRow((1 to 16).map(_.toFloat))
      val src1 = packRow(Seq.fill(16)(0.5f))

      writeUB(c, 0, src0)
      writeUB(c, 1, src1)
      c.clock.step(1)

      issueCmd(c, 0x10, src0 = 0, src1 = 1, dst = 2, len = 16)
      val cycles = waitDone(c)

      val result = readUB(c, 2)

      var errors = 0
      for (i <- 0 until 16) {
        val actual = extractFP16(result, i)
        val expected = (i + 1).toFloat + 0.5f
        if (math.abs(actual - expected) > 0.1f) {
          errors += 1
          println(f"VADD MISMATCH [$i]: expected=$expected%.1f actual=$actual%.1f")
        }
      }
      println(f"VADD test: $errors / 16 errors, completed in $cycles cycles")
      errors shouldBe 0
    }
  }

  "VectorCore VMUL" should "multiply two FP16 vectors element-wise" in {
    test(new VectorCore(p)) { c =>
      initVectorCore(c)

      val src0 = packRow(Seq.fill(16)(2.0f))
      val src1 = packRow((1 to 16).map(_.toFloat))

      writeUB(c, 0, src0)
      writeUB(c, 1, src1)
      c.clock.step(1)

      issueCmd(c, 0x12, src0 = 0, src1 = 1, dst = 2, len = 16)
      val cycles = waitDone(c)

      val result = readUB(c, 2)

      var errors = 0
      for (i <- 0 until 16) {
        val actual = extractFP16(result, i)
        val expected = 2.0f * (i + 1).toFloat
        if (math.abs(actual - expected) > 0.1f) {
          errors += 1
          println(f"VMUL MISMATCH [$i]: expected=$expected%.1f actual=$actual%.1f")
        }
      }
      println(f"VMUL test: $errors / 16 errors, completed in $cycles cycles")
      errors shouldBe 0
    }
  }

  "VectorCore VRELU" should "clamp negative FP16 values to zero" in {
    test(new VectorCore(p)) { c =>
      initVectorCore(c)

      val values = Seq(-8.0f, -4.0f, -2.0f, -1.0f, -0.5f, -0.25f, 0.0f, 0.25f,
                       0.5f, 1.0f, 2.0f, 4.0f, 8.0f, 16.0f, -16.0f, -32.0f)
      val src0 = packRow(values)

      writeUB(c, 0, src0)
      c.clock.step(1)

      issueCmd(c, 0x17, src0 = 0, src1 = 0, dst = 1, len = 16)
      val cycles = waitDone(c)

      val result = readUB(c, 1)

      var errors = 0
      for (i <- 0 until 16) {
        val actual = extractFP16(result, i)
        val expected = math.max(0.0f, values(i))
        if (math.abs(actual - expected) > 0.01f) {
          errors += 1
          println(f"VRELU MISMATCH [$i]: input=${values(i)}%.2f expected=$expected%.2f actual=$actual%.2f")
        }
      }
      println(f"VRELU test: $errors / 16 errors, completed in $cycles cycles")
      errors shouldBe 0
    }
  }

  "VectorCore VADD" should "handle multi-word vectors (32 elements = 2 words)" in {
    test(new VectorCore(p)) { c =>
      initVectorCore(c)

      val src0_w0 = packRow(Seq.fill(16)(1.0f))
      val src0_w1 = packRow(Seq.fill(16)(2.0f))
      val src1_w0 = packRow(Seq.fill(16)(3.0f))
      val src1_w1 = packRow(Seq.fill(16)(4.0f))

      writeUB(c, 0, src0_w0)
      writeUB(c, 1, src0_w1)
      writeUB(c, 2, src1_w0)
      writeUB(c, 3, src1_w1)
      c.clock.step(1)

      issueCmd(c, 0x10, src0 = 0, src1 = 2, dst = 4, len = 32)
      val cycles = waitDone(c)

      val result_w0 = readUB(c, 4)
      val result_w1 = readUB(c, 5)

      var errors = 0
      for (i <- 0 until 16) {
        val actual = extractFP16(result_w0, i)
        if (math.abs(actual - 4.0f) > 0.1f) {
          errors += 1
          println(f"VADD multi w0[$i]: expected=4.0 actual=$actual%.1f")
        }
      }
      for (i <- 0 until 16) {
        val actual = extractFP16(result_w1, i)
        if (math.abs(actual - 6.0f) > 0.1f) {
          errors += 1
          println(f"VADD multi w1[$i]: expected=6.0 actual=$actual%.1f")
        }
      }
      println(f"VADD multi-word test: $errors / 32 errors, completed in $cycles cycles")
      errors shouldBe 0
    }
  }
}
