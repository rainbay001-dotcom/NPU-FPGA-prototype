// Elaboration and reset smoke test for all NPU modules
package npu

import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import npu.common._
import npu.core._
import npu.vector._
import npu.fixpipe._
import npu.dma._
import npu.cluster._
import npu.soc._

class CubeCoreElaborationTest extends AnyFlatSpec with ChiselScalatestTester with Matchers {
  val p = NPUClusterParams(cubeCores = 1, vectorCores = 2, l2SizeKB = 64)

  "CubeCore" should "elaborate and idle after reset" in {
    test(new CubeCore(p)) { c =>
      c.io.cmd.valid.poke(false.B)
      c.io.l0c_out.ready.poke(false.B)
      Seq(c.io.l1, c.io.l0a, c.io.l0b).foreach { sp =>
        sp.read.en.poke(false.B); sp.read.addr.poke(0.U)
        sp.write.en.poke(false.B); sp.write.addr.poke(0.U); sp.write.data.poke(0.U)
      }
      c.clock.step(5)
      c.io.busy.peek().litValue shouldBe 0
    }
  }

  "VectorCore" should "elaborate and idle after reset" in {
    test(new VectorCore(p)) { c =>
      c.io.cmd.valid.poke(false.B)
      c.io.ub_ext.read.en.poke(false.B); c.io.ub_ext.read.addr.poke(0.U)
      c.io.ub_ext.write.en.poke(false.B); c.io.ub_ext.write.addr.poke(0.U)
      c.io.ub_ext.write.data.poke(0.U)
      c.clock.step(5)
      c.io.busy.peek().litValue shouldBe 0
    }
  }

  "Fixpipe" should "elaborate and idle after reset" in {
    test(new Fixpipe(p)) { c =>
      c.io.in.valid.poke(false.B)
      c.io.out.ready.poke(false.B)
      c.clock.step(5)
      c.io.out.valid.peek().litValue shouldBe 0
    }
  }

  "MTE" should "elaborate and idle after reset" in {
    test(new MTE(p)) { c =>
      c.io.cmd.valid.poke(false.B)
      c.io.mem.r.valid.poke(false.B)
      c.io.mem.ar.ready.poke(false.B)
      c.io.mem.aw.ready.poke(false.B)
      c.io.mem.w.ready.poke(false.B)
      c.clock.step(5)
      c.io.status.busy.peek().litValue shouldBe 0
    }
  }

  "NPUCluster" should "elaborate and idle after reset" in {
    test(new NPUCluster(p)) { c =>
      c.io.cmd.valid.poke(false.B)
      c.io.fixOut.ready.poke(false.B)
      c.io.mem.r.valid.poke(false.B)
      c.io.mem.ar.ready.poke(false.B)
      c.io.mem.aw.ready.poke(false.B)
      c.io.mem.w.ready.poke(false.B)
      c.clock.step(5)
      c.io.busy.peek().litValue shouldBe 0
    }
  }

  "NPUSoC" should "elaborate and idle after reset" in {
    test(new NPUSoC(p)) { c =>
      c.io.cmdWrite.valid.poke(false.B)
      c.io.fixOut.ready.poke(false.B)
      c.io.mem.r.valid.poke(false.B)
      c.io.mem.ar.ready.poke(false.B)
      c.io.mem.aw.ready.poke(false.B)
      c.io.mem.w.ready.poke(false.B)
      c.clock.step(5)
      c.io.status.busy.peek().litValue shouldBe 0
    }
  }
}
