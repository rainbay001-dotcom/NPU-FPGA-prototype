// Elaboration and reset smoke test for all Da Vinci NPU modules
package davinci

import chisel3._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import davinci.common._
import davinci.core._
import davinci.vector._
import davinci.fixpipe._
import davinci.dma._
import davinci.cluster._
import davinci.soc._

class CubeCoreElaborationTest extends AnyFlatSpec with Matchers {
  val p = NPUClusterParams(cubeCores = 1, vectorCores = 2, l2SizeKB = 64)

  // Helper: assert reset then release
  private def resetAndStep(c: { val clock: chisel3.Clock; val reset: chisel3.Reset }, n: Int = 5): Unit = {
    c.reset.asInstanceOf[chisel3.Bool].poke(true.B)
    c.clock.step(1)
    c.reset.asInstanceOf[chisel3.Bool].poke(false.B)
    c.clock.step(n)
  }

  "CubeCore" should "elaborate and idle after reset" in {
    simulate(new CubeCore(p)) { c =>
      c.reset.poke(true.B); c.clock.step(1); c.reset.poke(false.B)
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
    simulate(new VectorCore(p)) { c =>
      c.reset.poke(true.B); c.clock.step(1); c.reset.poke(false.B)
      c.io.cmd.valid.poke(false.B)
      c.io.ub_ext.read.en.poke(false.B); c.io.ub_ext.read.addr.poke(0.U)
      c.io.ub_ext.write.en.poke(false.B); c.io.ub_ext.write.addr.poke(0.U)
      c.io.ub_ext.write.data.poke(0.U)
      c.clock.step(5)
      c.io.busy.peek().litValue shouldBe 0
    }
  }

  "Fixpipe" should "elaborate and idle after reset" in {
    simulate(new Fixpipe(p)) { c =>
      c.reset.poke(true.B); c.clock.step(1); c.reset.poke(false.B)
      c.io.in.valid.poke(false.B)
      c.io.out.ready.poke(false.B)
      c.clock.step(5)
      c.io.out.valid.peek().litValue shouldBe 0
    }
  }

  "MTE" should "elaborate and idle after reset" in {
    simulate(new MTE(p)) { c =>
      c.reset.poke(true.B); c.clock.step(1); c.reset.poke(false.B)
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
    simulate(new NPUCluster(p)) { c =>
      c.reset.poke(true.B); c.clock.step(1); c.reset.poke(false.B)
      c.io.cmd.valid.poke(false.B)
      c.io.mem.r.valid.poke(false.B)
      c.io.mem.ar.ready.poke(false.B)
      c.io.mem.aw.ready.poke(false.B)
      c.io.mem.w.ready.poke(false.B)
      c.clock.step(5)
      c.io.busy.peek().litValue shouldBe 0
    }
  }

  "DaVinciSoC" should "elaborate and idle after reset" in {
    simulate(new DaVinciSoC(p)) { c =>
      c.reset.poke(true.B); c.clock.step(1); c.reset.poke(false.B)
      c.io.cmdWrite.valid.poke(false.B)
      c.io.mem.r.valid.poke(false.B)
      c.io.mem.ar.ready.poke(false.B)
      c.io.mem.aw.ready.poke(false.B)
      c.io.mem.w.ready.poke(false.B)
      c.clock.step(5)
      c.io.status.busy.peek().litValue shouldBe 0
    }
  }
}
