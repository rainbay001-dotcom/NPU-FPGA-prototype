// End-to-end test: HBM → L2 → L1 → L0A/L0B → CUBE MMAD → Fixpipe → verify output
// Tests the full NPUSoC data path through MTE DMA + command dispatch + compute
package npu

import chisel3._
import chiseltest._
import chiseltest.simulator.VerilatorBackendAnnotation
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import npu.common._
import npu.cluster._
import npu.soc._
import npu.dma._

class EndToEndTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  val p = NPUClusterParams(cubeCores = 1, vectorCores = 2, l2SizeKB = 64)

  // ---- FP16/FP32 helpers ----
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

    c.io.cmdWrite.bits.fixCfg.enableDequant.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableBias.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableRequant.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableRelu.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.enableNZ2ND.poke(false.B)
    c.io.cmdWrite.bits.fixCfg.biasAddr.poke(0.U)
    c.io.cmdWrite.bits.fixCfg.outputToUB.poke(false.B)

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

  // Stateful AXI memory model
  var pendingReadAddr: Option[Long] = None

  def driveAxi(c: NPUSoC): Unit = {
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

    if (c.io.mem.ar.valid.peek().litValue == 1) {
      pendingReadAddr = Some(c.io.mem.ar.bits.addr.peek().litValue.toLong)
    }

    c.io.mem.ar.ready.poke(true.B)
    c.io.mem.aw.ready.poke(true.B)
    c.io.mem.w.ready.poke(true.B)
  }

  def waitMteDone(c: NPUSoC, maxCycles: Int = 200): Int = {
    var cycles = 0
    while (c.io.status.mteBusy.peek().litValue == 0 && cycles < 20) {
      driveAxi(c)
      c.clock.step(1)
      cycles += 1
    }
    while (c.io.status.mteBusy.peek().litValue == 1 && cycles < maxCycles) {
      driveAxi(c)
      c.clock.step(1)
      cycles += 1
    }
    cycles
  }

  "NPUSoC" should "execute full HBM→L2→L1→L0→CUBE→Fixpipe data path" in {
    val aRow = packRow(Seq.fill(16)(1.0f))
    for (i <- 0 until 16) { hbmStore(i.toLong * 32, aRow) }
    val bRow = packRow(Seq.fill(16)(2.0f))
    for (i <- 0 until 16) { hbmStore(512L + i.toLong * 32, bRow) }

    test(new NPUSoC(p)).withAnnotations(Seq(VerilatorBackendAnnotation)) { c =>
      pendingReadAddr = None

      c.io.mem.r.valid.poke(false.B)
      c.io.mem.r.bits.data.poke(0.U)
      c.io.mem.r.bits.last.poke(true.B)
      c.io.mem.ar.ready.poke(true.B)
      c.io.mem.aw.ready.poke(true.B)
      c.io.mem.w.ready.poke(true.B)
      c.io.fixOut.ready.poke(true.B)
      c.clock.step(2)

      println("Step 1: DMA HBM→L2 tile A")
      sendCmd(c, target = 2, coreIdx = 0, dmaCmd = Some((0x30, 0L, 0L, 16)))
      val dma1 = waitMteDone(c)
      println(s"  DMA HBM→L2 A done in $dma1 cycles")

      println("Step 2: DMA L2→L1 tile A")
      sendCmd(c, target = 2, coreIdx = 0, dmaCmd = Some((0x31, 0L, 0L, 16)))
      val dma2 = waitMteDone(c)
      println(s"  DMA L2→L1 A done in $dma2 cycles")

      println("Step 3: DMA L1→L0A tile A")
      sendCmd(c, target = 2, coreIdx = 0, dmaCmd = Some((0x32, 0L, 0L, 16)))
      val dma3 = waitMteDone(c)
      println(s"  DMA L1→L0A A done in $dma3 cycles")

      println("Step 4: DMA HBM→L2 tile B")
      sendCmd(c, target = 2, coreIdx = 0, dmaCmd = Some((0x30, 512L, 16L, 16)))
      val dma4 = waitMteDone(c)
      println(s"  DMA HBM→L2 B done in $dma4 cycles")

      println("Step 5: DMA L2→L1 tile B")
      sendCmd(c, target = 2, coreIdx = 0, dmaCmd = Some((0x31, 16L, 16L, 16)))
      val dma5 = waitMteDone(c)
      println(s"  DMA L2→L1 B done in $dma5 cycles")

      println("Step 6: DMA L1→L0B tile B")
      sendCmd(c, target = 2, coreIdx = 0, dmaCmd = Some((0x33, 16L, 0L, 16)))
      val dma6 = waitMteDone(c)
      println(s"  DMA L1→L0B B done in $dma6 cycles")

      println("Step 7: MMAD")
      sendCmd(c, target = 0, coreIdx = 0, cubeCmd = Some((0x01, 0, 0, 0, 1, false)))

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
