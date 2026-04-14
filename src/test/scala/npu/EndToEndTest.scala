// End-to-end test: HBM → L2 → L1 → L0A/L0B → CUBE MMAD → Fixpipe → verify output
// Tests the full NPUSoC data path through MTE DMA + command dispatch + compute
package npu

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import npu.common._
import npu.cluster._
import npu.soc._
import npu.dma._

class EndToEndTest extends AnyFlatSpec with Matchers {
  val p = NPUClusterParams(cubeCores = 1, vectorCores = 2, l2SizeKB = 64)

  // ---- FP16/FP32 helpers (same as CubeFunctionalTest) ----
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

  def packRow(values: Seq[Float]): BigInt = {
    require(values.length == 16)
    values.zipWithIndex.foldLeft(BigInt(0)) { case (acc, (v, i)) =>
      acc | (BigInt(fp16(v) & 0xFFFF) << (i * 16))
    }
  }

  def extractFP32(word: BigInt, index: Int): Float = {
    val bits = ((word >> (index * 32)) & BigInt(0xFFFFFFFFL)).toInt
    java.lang.Float.intBitsToFloat(bits)
  }

  // ---- Simulated HBM memory ----
  // Addressed in 256-bit (32-byte) words; AXI uses byte addresses
  val hbm = scala.collection.mutable.Map[Long, BigInt]()

  def hbmStore(byteAddr: Long, data: BigInt): Unit = {
    hbm(byteAddr / 32) = data
  }

  def hbmLoad(byteAddr: Long): BigInt = {
    hbm.getOrElse(byteAddr / 32, BigInt(0))
  }

  // ---- Command builder helpers ----
  def sendCmd(c: NPUSoC, target: Int, coreIdx: Int,
              cubeCmd: Option[(Int, Int, Int, Int, Int, Boolean)] = None,
              dmaCmd: Option[(Int, Long, Long, Int)] = None): Unit = {
    c.io.cmdWrite.valid.poke(true.B)
    c.io.cmdWrite.bits.target.poke(target.U)
    c.io.cmdWrite.bits.coreIdx.poke(coreIdx.U)

    // DMA fields
    dmaCmd match {
      case Some((opcode, src, dst, length)) =>
        c.io.cmdWrite.bits.dmaCmd.opcode.poke(opcode.U)
        c.io.cmdWrite.bits.dmaCmd.srcAddr.poke(src.U)
        c.io.cmdWrite.bits.dmaCmd.dstAddr.poke(dst.U)
        c.io.cmdWrite.bits.dmaCmd.length.poke(length.U)
        c.io.cmdWrite.bits.dmaCmd.nd2nz.poke(false.B)
        c.io.cmdWrite.bits.dmaCmd.nz2nd.poke(false.B)
      case None =>
        c.io.cmdWrite.bits.dmaCmd.opcode.poke(0.U)
        c.io.cmdWrite.bits.dmaCmd.srcAddr.poke(0.U)
        c.io.cmdWrite.bits.dmaCmd.dstAddr.poke(0.U)
        c.io.cmdWrite.bits.dmaCmd.length.poke(0.U)
        c.io.cmdWrite.bits.dmaCmd.nd2nz.poke(false.B)
        c.io.cmdWrite.bits.dmaCmd.nz2nd.poke(false.B)
    }

    // CUBE fields
    cubeCmd match {
      case Some((opcode, l0aAddr, l0bAddr, l0cAddr, kTiles, accum)) =>
        c.io.cmdWrite.bits.cubeCmd.opcode.poke(opcode.U)
        c.io.cmdWrite.bits.cubeCmd.l0a_addr.poke(l0aAddr.U)
        c.io.cmdWrite.bits.cubeCmd.l0b_addr.poke(l0bAddr.U)
        c.io.cmdWrite.bits.cubeCmd.l0c_addr.poke(l0cAddr.U)
        c.io.cmdWrite.bits.cubeCmd.k_tiles.poke(kTiles.U)
        c.io.cmdWrite.bits.cubeCmd.accum.poke(accum.B)
      case None =>
        c.io.cmdWrite.bits.cubeCmd.opcode.poke(0.U)
        c.io.cmdWrite.bits.cubeCmd.l0a_addr.poke(0.U)
        c.io.cmdWrite.bits.cubeCmd.l0b_addr.poke(0.U)
        c.io.cmdWrite.bits.cubeCmd.l0c_addr.poke(0.U)
        c.io.cmdWrite.bits.cubeCmd.k_tiles.poke(0.U)
        c.io.cmdWrite.bits.cubeCmd.accum.poke(false.B)
    }

    // Fixpipe config: passthrough (no dequant, no bias, no relu)
    c.io.cmdWrite.bits.fixCfg.enableDequant.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableBias.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableRequant.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableRelu.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableNZ2ND.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.biasAddr.poke(0.U)
    c.io.cmdWrite.bits.fixCfg.outputToUB.poke(false.B)

    // Vector fields (unused but must be driven)
    c.io.cmdWrite.bits.vecCmd.opcode.poke(0.U)
    c.io.cmdWrite.bits.vecCmd.src0.poke(0.U)
    c.io.cmdWrite.bits.vecCmd.src1.poke(0.U)
    c.io.cmdWrite.bits.vecCmd.dst.poke(0.U)
    c.io.cmdWrite.bits.vecCmd.len.poke(0.U)
    c.io.cmdWrite.bits.vecCmd.scalar.poke(0.U)
    c.io.cmdWrite.bits.vecCmd.useScalar.poke(false.B)

    c.clock.step(1)
    c.io.cmdWrite.valid.poke(false.B)
  }

  def waitIdle(c: NPUSoC, maxCycles: Int = 500): Int = {
    var cycles = 0
    while (c.io.status.busy.peek().litValue == 1 && cycles < maxCycles) {
      // Drive AXI memory responses
      driveAxi(c)
      c.clock.step(1)
      cycles += 1
    }
    cycles
  }

  // Stateful AXI memory model: latch read address on ar handshake,
  // present data on r channel one cycle later (matching SyncReadMem latency)
  var pendingReadAddr: Option[Long] = None

  def driveAxi(c: NPUSoC): Unit = {
    // Present data from previously latched read request
    pendingReadAddr match {
      case Some(addr) =>
        c.io.mem.r.valid.poke(true.B)
        c.io.mem.r.bits.data.poke(hbmLoad(addr).U)
        c.io.mem.r.bits.last.poke(true.B)
        pendingReadAddr = None
      case None =>
        c.io.mem.r.valid.poke(false.B)
        c.io.mem.r.bits.data.poke(0.U)
        c.io.mem.r.bits.last.poke(true.B)
    }

    // Latch new read address request (will be served next cycle)
    if (c.io.mem.ar.valid.peek().litValue == 1) {
      pendingReadAddr = Some(c.io.mem.ar.bits.addr.peek().litValue.toLong)
    }

    // Always accept requests
    c.io.mem.ar.ready.poke(true.B)
    c.io.mem.aw.ready.poke(true.B)
    c.io.mem.w.ready.poke(true.B)
  }

  // Wait for MTE to finish: first wait for busy to go HIGH (command queue delay),
  // then wait for it to go LOW (transfer complete)
  def waitMteDone(c: NPUSoC, maxCycles: Int = 200): Int = {
    var cycles = 0
    // Wait for MTE to start
    while (c.io.status.mteBusy.peek().litValue == 0 && cycles < 20) {
      driveAxi(c)
      c.clock.step(1)
      cycles += 1
    }
    // Wait for MTE to finish
    while (c.io.status.mteBusy.peek().litValue == 1 && cycles < maxCycles) {
      driveAxi(c)
      c.clock.step(1)
      cycles += 1
    }
    cycles
  }

  "NPUSoC" should "execute full HBM→L2→L1→L0→CUBE→Fixpipe data path" in {
    // Test: A = all 1.0, B = all 2.0
    // Expected: C[m][n] = sum_k(1.0 * 2.0) = 32.0 for all m,n

    // Populate simulated HBM with tile data
    // Tile A (16 rows × 16 FP16): 16 words at byte addr 0..511
    val aRow = packRow(Seq.fill(16)(1.0f))
    for (i <- 0 until 16) {
      hbmStore(i.toLong * 32, aRow)
    }
    // Tile B (16 rows × 16 FP16): 16 words at byte addr 512..1023
    val bRow = packRow(Seq.fill(16)(2.0f))
    for (i <- 0 until 16) {
      hbmStore(512L + i.toLong * 32, bRow)
    }

    simulate(new NPUSoC(p)) { c =>
      pendingReadAddr = None  // reset AXI state

      c.reset.poke(true.B)
      c.clock.step(2)
      c.reset.poke(false.B)

      // Default AXI signals
      c.io.mem.r.valid.poke(false.B)
      c.io.mem.r.bits.data.poke(0.U)
      c.io.mem.r.bits.last.poke(true.B)
      c.io.mem.ar.ready.poke(true.B)
      c.io.mem.aw.ready.poke(true.B)
      c.io.mem.w.ready.poke(true.B)

      // Accept fixpipe output
      c.io.fixOut.ready.poke(true.B)

      c.clock.step(2)

      // ---- Step 1: DMA HBM → L2 for tile A (16 words from byte addr 0) ----
      println("Step 1: DMA HBM→L2 tile A")
      sendCmd(c, target = 2, coreIdx = 0,
        dmaCmd = Some((0x30, 0L, 0L, 16)))  // DMA_HBM_TO_L2, src=0, dst=0, len=16
      val dma1 = waitMteDone(c)
      println(s"  DMA HBM→L2 A done in $dma1 cycles")

      // ---- Step 2: DMA L2 → L1 for tile A ----
      println("Step 2: DMA L2→L1 tile A")
      sendCmd(c, target = 2, coreIdx = 0,
        dmaCmd = Some((0x31, 0L, 0L, 16)))  // DMA_L2_TO_L1, src=0, dst=0, len=16
      val dma2 = waitMteDone(c)
      println(s"  DMA L2→L1 A done in $dma2 cycles")

      // ---- Step 3: DMA L1 → L0A for tile A ----
      println("Step 3: DMA L1→L0A tile A")
      sendCmd(c, target = 2, coreIdx = 0,
        dmaCmd = Some((0x32, 0L, 0L, 16)))  // DMA_L1_TO_L0A, src=0, dst=0, len=16
      val dma3 = waitMteDone(c)
      println(s"  DMA L1→L0A A done in $dma3 cycles")

      // ---- Step 4: DMA HBM → L2 for tile B (from byte addr 512) ----
      println("Step 4: DMA HBM→L2 tile B")
      sendCmd(c, target = 2, coreIdx = 0,
        dmaCmd = Some((0x30, 512L, 16L, 16)))  // DMA_HBM_TO_L2, src=512, dst=16, len=16
      val dma4 = waitMteDone(c)
      println(s"  DMA HBM→L2 B done in $dma4 cycles")

      // ---- Step 5: DMA L2 → L1 for tile B ----
      println("Step 5: DMA L2→L1 tile B")
      sendCmd(c, target = 2, coreIdx = 0,
        dmaCmd = Some((0x31, 16L, 16L, 16)))  // DMA_L2_TO_L1, src=16, dst=16, len=16
      val dma5 = waitMteDone(c)
      println(s"  DMA L2→L1 B done in $dma5 cycles")

      // ---- Step 6: DMA L1 → L0B for tile B ----
      println("Step 6: DMA L1→L0B tile B")
      sendCmd(c, target = 2, coreIdx = 0,
        dmaCmd = Some((0x33, 16L, 0L, 16)))  // DMA_L1_TO_L0B, src=16, dst=0, len=16
      val dma6 = waitMteDone(c)
      println(s"  DMA L1→L0B B done in $dma6 cycles")

      // ---- Step 7: MMAD command to CUBE core 0 ----
      println("Step 7: MMAD")
      sendCmd(c, target = 0, coreIdx = 0,
        cubeCmd = Some((0x01, 0, 0, 0, 1, false)))  // MMAD, l0a=0, l0b=0, l0c=0, k=1, no accum

      // ---- Step 8: Collect Fixpipe output (32 words of FP32 results) ----
      println("Step 8: Collecting Fixpipe output...")
      val outputWords = scala.collection.mutable.ArrayBuffer[BigInt]()
      var cycles = 0
      while (outputWords.length < 32 && cycles < 300) {
        driveAxi(c)
        if (c.io.fixOut.valid.peek().litValue == 1) {
          outputWords += c.io.fixOut.bits.data.peek().litValue
        }
        c.clock.step(1)
        cycles += 1
      }

      println(s"  Collected ${outputWords.length} words in $cycles cycles")
      outputWords.length shouldBe 32

      // ---- Step 9: Verify results ----
      // C[m][n] = sum_k(1.0 * 2.0) = 16 * 2.0 = 32.0
      val expected = 32.0f
      var errors = 0
      for (wordIdx <- 0 until 32) {
        val row = wordIdx / 2
        val halfOffset = if (wordIdx % 2 == 0) 0 else 8
        for (elemIdx <- 0 until 8) {
          val col = halfOffset + elemIdx
          val actual = extractFP32(outputWords(wordIdx), elemIdx)
          if (math.abs(actual - expected) > 0.5f) {
            errors += 1
            if (errors <= 10) {
              println(f"  MISMATCH C[$row][$col]: expected=$expected%.1f actual=$actual%.1f")
            }
          }
        }
      }

      println(f"End-to-end result: $errors / ${32 * 8} errors")
      errors shouldBe 0
    }
  }
}
