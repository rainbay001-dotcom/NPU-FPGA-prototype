// Functional test: load known FP16 tiles into CubeCore, run mmad, verify FP32 output
package npu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import npu.common._
import npu.core._

class CubeFunctionalTest extends AnyFlatSpec with Matchers {
  val p = NPUClusterParams(cubeCores = 1, vectorCores = 2, l2SizeKB = 64)

  // IEEE 754 FP16 encoding helpers
  // FP16: 1 sign + 5 exp + 10 mantissa, bias=15
  def fp16(value: Float): Int = {
    if (value == 0.0f) return 0
    val bits = java.lang.Float.floatToIntBits(value)
    val sign = (bits >> 31) & 1
    val exp  = ((bits >> 23) & 0xFF) - 127 + 15  // rebias from FP32 to FP16
    val man  = (bits >> 13) & 0x3FF               // top 10 bits of 23-bit mantissa
    if (exp <= 0) return (sign << 15)              // underflow → ±0
    if (exp >= 31) return (sign << 15) | 0x7C00    // overflow → ±inf
    (sign << 15) | (exp << 10) | man
  }

  // Pack 16 FP16 values into one 256-bit word (as BigInt)
  def packRow(values: Seq[Float]): BigInt = {
    require(values.length == 16)
    values.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(fp16(v) & 0xFFFF) << (i * 16))
    }
  }

  // Extract FP32 from a 256-bit word (8 FP32 values per word)
  def extractFP32(word: BigInt, index: Int): Float = {
    val bits = ((word >> (index * 32)) & BigInt(0xFFFFFFFFL)).toInt
    java.lang.Float.intBitsToFloat(bits)
  }

  "CubeCore" should "compute a simple identity × constant matrix multiply" in {
    // Test: A = identity-like (1.0 on diagonal, 0 elsewhere for row 0-3)
    // Simpler: A = all 1.0, B = all 2.0
    // Expected: C[m][n] = sum_k(A[m][k] * B[k][n]) = sum_k(1.0 * 2.0) = 16 * 2.0 = 32.0

    simulate(new CubeCore(p)) { c =>
      // Reset
      c.reset.poke(true.B)
      c.clock.step(1)
      c.reset.poke(false.B)

      // Drive unused ports to safe defaults
      c.io.cmd.valid.poke(false.B)
      c.io.l0c_out.ready.poke(false.B)
      c.io.l1.read.en.poke(false.B); c.io.l1.read.addr.poke(0.U)
      c.io.l1.write.en.poke(false.B); c.io.l1.write.addr.poke(0.U); c.io.l1.write.data.poke(0.U)
      c.io.l0a.read.en.poke(false.B); c.io.l0a.read.addr.poke(0.U)
      c.io.l0b.read.en.poke(false.B); c.io.l0b.read.addr.poke(0.U)
      c.clock.step(2)

      // ---- Step 1: Write A tile (all 1.0) into L0A via DMA port ----
      // A is 16 rows, each row = 16 FP16 values = one 256-bit word
      val aRow = packRow(Seq.fill(16)(1.0f))
      for (i <- 0 until 16) {
        c.io.l0a.write.en.poke(true.B)
        c.io.l0a.write.addr.poke(i.U)
        c.io.l0a.write.data.poke(aRow.U)
        c.clock.step(1)
      }
      c.io.l0a.write.en.poke(false.B)

      // ---- Step 2: Write B tile (all 2.0) into L0B via DMA port ----
      val bRow = packRow(Seq.fill(16)(2.0f))
      for (i <- 0 until 16) {
        c.io.l0b.write.en.poke(true.B)
        c.io.l0b.write.addr.poke(i.U)
        c.io.l0b.write.data.poke(bRow.U)
        c.clock.step(1)
      }
      c.io.l0b.write.en.poke(false.B)
      c.clock.step(1)

      // ---- Step 3: Issue MMAD command ----
      c.io.cmd.valid.poke(true.B)
      c.io.cmd.bits.opcode.poke(0x01.U)   // MMAD
      c.io.cmd.bits.l0a_addr.poke(0.U)
      c.io.cmd.bits.l0b_addr.poke(0.U)
      c.io.cmd.bits.l0c_addr.poke(0.U)
      c.io.cmd.bits.k_tiles.poke(1.U)
      c.io.cmd.bits.accum.poke(false.B)
      c.clock.step(1)
      c.io.cmd.valid.poke(false.B)

      // ---- Step 4: Wait for compute to finish and drain ----
      // FSM: sLoadA(16) + sLoadB(16) + sCompute(16) + sDrain(32) = 80 cycles
      // But we need to accept the drain output
      c.io.l0c_out.ready.poke(true.B)

      // Wait for drain to start (up to 60 cycles for load+compute)
      var cycles = 0
      while (c.io.l0c_out.valid.peek().litValue == 0 && cycles < 100) {
        c.clock.step(1)
        cycles += 1
      }

      if (cycles >= 100) {
        fail(s"CubeCore never started draining after $cycles cycles")
      }

      // ---- Step 5: Collect all 32 output words ----
      val outputWords = scala.collection.mutable.ArrayBuffer[BigInt]()
      var drainCycles = 0
      while (drainCycles < 100 && outputWords.length < 32) {
        if (c.io.l0c_out.valid.peek().litValue == 1) {
          outputWords += c.io.l0c_out.bits.data.peek().litValue
        }
        c.clock.step(1)
        drainCycles += 1
      }

      outputWords.length shouldBe 32

      // ---- Step 6: Verify results ----
      // Each output word has 8 FP32 values
      // C[m][n] = sum_k(1.0 * 2.0) = 32.0 for all m,n
      val expected = 32.0f
      var errors = 0
      for (wordIdx <- 0 until 32) {
        for (elemIdx <- 0 until 8) {
          val actual = extractFP32(outputWords(wordIdx), elemIdx)
          val row = wordIdx / 2
          val col = if (wordIdx % 2 == 0) elemIdx else elemIdx + 8
          if (math.abs(actual - expected) > 0.5f) {
            errors += 1
            if (errors <= 5) {
              println(f"MISMATCH C[$row][$col]: expected=$expected%.1f actual=$actual%.1f " +
                f"(raw=0x${((outputWords(wordIdx) >> (elemIdx * 32)) & BigInt(0xFFFFFFFFL)).toLong}%08X)")
            }
          }
        }
      }

      if (errors > 0) {
        // Print first output word for debugging
        val w0 = outputWords(0)
        println(f"First output word (raw): 0x${w0.toString(16)}")
        for (i <- 0 until 8) {
          println(f"  elem[$i] = ${extractFP32(w0, i)}%.4f (0x${((w0 >> (i*32)) & BigInt(0xFFFFFFFFL)).toLong}%08X)")
        }
      }

      println(f"Total errors: $errors / ${32 * 8} elements")
      errors shouldBe 0
    }
  }

  it should "compute A=identity × B and get B back" in {
    // A = identity matrix (1.0 on diagonal, 0 elsewhere)
    // B = row i has all values = (i+1).0
    // Expected: C = A × B = B (identity preserves B)

    simulate(new CubeCore(p)) { c =>
      c.reset.poke(true.B)
      c.clock.step(1)
      c.reset.poke(false.B)

      c.io.cmd.valid.poke(false.B)
      c.io.l0c_out.ready.poke(false.B)
      c.io.l1.read.en.poke(false.B); c.io.l1.read.addr.poke(0.U)
      c.io.l1.write.en.poke(false.B); c.io.l1.write.addr.poke(0.U); c.io.l1.write.data.poke(0.U)
      c.io.l0a.read.en.poke(false.B); c.io.l0a.read.addr.poke(0.U)
      c.io.l0b.read.en.poke(false.B); c.io.l0b.read.addr.poke(0.U)
      c.clock.step(2)

      // Write A = identity matrix into L0A
      // Row m: element k = 1.0 if k==m, else 0.0
      for (m <- 0 until 16) {
        val row = (0 until 16).map(k => if (k == m) 1.0f else 0.0f)
        c.io.l0a.write.en.poke(true.B)
        c.io.l0a.write.addr.poke(m.U)
        c.io.l0a.write.data.poke(packRow(row).U)
        c.clock.step(1)
      }
      c.io.l0a.write.en.poke(false.B)

      // Write B: row k has all values = (k+1).0
      for (k <- 0 until 16) {
        val row = Seq.fill(16)((k + 1).toFloat)
        c.io.l0b.write.en.poke(true.B)
        c.io.l0b.write.addr.poke(k.U)
        c.io.l0b.write.data.poke(packRow(row).U)
        c.clock.step(1)
      }
      c.io.l0b.write.en.poke(false.B)
      c.clock.step(1)

      // Issue MMAD
      c.io.cmd.valid.poke(true.B)
      c.io.cmd.bits.opcode.poke(0x01.U)
      c.io.cmd.bits.l0a_addr.poke(0.U)
      c.io.cmd.bits.l0b_addr.poke(0.U)
      c.io.cmd.bits.l0c_addr.poke(0.U)
      c.io.cmd.bits.k_tiles.poke(1.U)
      c.io.cmd.bits.accum.poke(false.B)
      c.clock.step(1)
      c.io.cmd.valid.poke(false.B)

      c.io.l0c_out.ready.poke(true.B)

      // Wait for drain
      var cycles = 0
      while (c.io.l0c_out.valid.peek().litValue == 0 && cycles < 100) {
        c.clock.step(1)
        cycles += 1
      }

      // Collect output
      val outputWords = scala.collection.mutable.ArrayBuffer[BigInt]()
      var drainCycles = 0
      while (drainCycles < 100 && outputWords.length < 32) {
        if (c.io.l0c_out.valid.peek().litValue == 1) {
          outputWords += c.io.l0c_out.bits.data.peek().litValue
        }
        c.clock.step(1)
        drainCycles += 1
      }

      outputWords.length shouldBe 32

      // Verify: C = I × B = B
      // C[m][n] = sum_k(I[m][k] * B[k][n]) = B[m][n] = (m+1).0
      var errors = 0
      for (wordIdx <- 0 until 32) {
        val row = wordIdx / 2
        val halfOffset = if (wordIdx % 2 == 0) 0 else 8
        val expected = (row + 1).toFloat
        for (elemIdx <- 0 until 8) {
          val col = halfOffset + elemIdx
          val actual = extractFP32(outputWords(wordIdx), elemIdx)
          if (math.abs(actual - expected) > 0.5f) {
            errors += 1
            if (errors <= 10) {
              println(f"MISMATCH C[$row][$col]: expected=$expected%.1f actual=$actual%.1f")
            }
          }
        }
      }

      println(f"Identity test: $errors / ${32 * 8} errors")
      errors shouldBe 0
    }
  }
}
