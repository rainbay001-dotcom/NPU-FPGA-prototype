// NPU Cluster — Top-level integration of CUBE + VECTOR + Fixpipe + MTE + shared L2
// This is the "NPU die" equivalent: parametric number of CUBE/VECTOR cores
// sharing a common L2 cache with an external memory port (AXI4) to DDR/HBM.
//
// Architecture (split-mode NPU):
//   Task Scheduler → dispatches commands to CUBE / VECTOR / MTE
//   MTE → moves data between memory levels
//   CUBE core → reads L0A/L0B, computes mmad, writes L0C
//   Fixpipe → post-processes L0C → L1 or UB
//   VECTOR core → reads/writes UB, executes element-wise ops
//
// Interconnect: L2 is shared by all cores. Each core has private L0/L1/UB.
package npu.cluster

import chisel3._
import chisel3.util._
import npu.common._
import npu.core._
import npu.vector._
import npu.fixpipe._
import npu.dma._

// Command from the Task Scheduler (RISC-V control core)
class NPUCommand extends Bundle {
  val target   = UInt(8.W)   // 0=CUBE, 1=VECTOR, 2=MTE, 3=FIXPIPE
  val coreIdx  = UInt(8.W)   // which core instance
  // Union of all command types (wasteful but simple for prototype)
  val cubeCmd  = new CubeCommand(NPUClusterParams())
  val vecCmd   = new VectorCommand
  val dmaCmd   = new DMACommand
  val fixCfg   = new FixpipeConfig
}

class NPUClusterIO(val p: NPUClusterParams) extends Bundle {
  // Command queue from Task Scheduler
  val cmd = Flipped(Decoupled(new NPUCommand))

  // Status
  val busy = Output(Bool())
  val cubeBusy   = Output(Vec(p.cubeCores, Bool()))
  val vectorBusy = Output(Vec(p.vectorCores, Bool()))
  val mteBusy    = Output(Bool())

  // External memory port (to DDR/HBM controller)
  val mem = new MemPortIO(p.addrWidth, p.dataWidth)

  // Debug: Fixpipe output from first CUBE core (for end-to-end testing)
  val fixOut = Decoupled(new Bundle {
    val data = UInt(256.W)
    val last = Bool()
  })
}

class NPUCluster(val p: NPUClusterParams) extends Module {
  val io = IO(new NPUClusterIO(p))

  // ---- Shared L2 cache ----
  val l2Depth = p.l2SizeKB * 1024 / 32  // 256-bit words
  val l2 = SyncReadMem(l2Depth, UInt(256.W))

  // ---- Instantiate cores ----
  val cubes    = Seq.fill(p.cubeCores)(Module(new CubeCore(p)))
  val fixpipes = Seq.fill(p.cubeCores)(Module(new Fixpipe(p)))  // 1 fixpipe per CUBE
  val vectors  = Seq.fill(p.vectorCores)(Module(new VectorCore(p)))
  val mte      = Module(new MTE(p))

  // ---- Default wiring ----
  // CUBE cores: connect L0C output → Fixpipe input
  cubes.zip(fixpipes).foreach { case (cube, fix) =>
    fix.io.in <> cube.io.l0c_out
    cube.io.cmd.valid := false.B
    cube.io.cmd.bits  := DontCare
    fix.io.config     := DontCare
  }

  // Wire first fixpipe output to debug port; rest always accept
  if (p.cubeCores > 0) {
    io.fixOut.valid     := fixpipes(0).io.out.valid
    io.fixOut.bits.data := fixpipes(0).io.out.bits.data
    io.fixOut.bits.last := fixpipes(0).io.out.bits.last
    fixpipes(0).io.out.ready := io.fixOut.ready
    fixpipes.drop(1).foreach(_.io.out.ready := true.B)
  } else {
    io.fixOut.valid     := false.B
    io.fixOut.bits.data := 0.U
    io.fixOut.bits.last := false.B
  }

  // VECTOR cores
  vectors.foreach { v =>
    v.io.cmd.valid := false.B
    v.io.cmd.bits  := DontCare
    v.io.ub_ext.read.en := false.B
    v.io.ub_ext.read.addr := 0.U
    v.io.ub_ext.write.en := false.B
    v.io.ub_ext.write.addr := 0.U
    v.io.ub_ext.write.data := 0.U
  }

  // MTE
  mte.io.cmd.valid := false.B
  mte.io.cmd.bits  := DontCare

  // MTE L2 port
  val l2ReadAddr = Wire(UInt(log2Ceil(l2Depth).W))
  val l2WriteAddr = Wire(UInt(log2Ceil(l2Depth).W))
  val l2WriteData = Wire(UInt(256.W))
  val l2ReadEn  = Wire(Bool())
  val l2WriteEn = Wire(Bool())

  l2ReadAddr  := mte.io.l2.read.addr
  l2ReadEn    := mte.io.l2.read.en
  l2WriteAddr := mte.io.l2.write.addr
  l2WriteData := mte.io.l2.write.data
  l2WriteEn   := mte.io.l2.write.en

  mte.io.l2.read.data := l2.read(l2ReadAddr, l2ReadEn)
  when(l2WriteEn) {
    l2.write(l2WriteAddr, l2WriteData)
  }

  // MTE ↔ CUBE core scratchpads (connect to first CUBE core; real design would mux)
  if (p.cubeCores > 0) {
    // L1
    cubes(0).io.l1.read.en    := mte.io.l1.read.en
    cubes(0).io.l1.read.addr  := mte.io.l1.read.addr
    mte.io.l1.read.data       := cubes(0).io.l1.read.data
    cubes(0).io.l1.write.en   := mte.io.l1.write.en
    cubes(0).io.l1.write.addr := mte.io.l1.write.addr
    cubes(0).io.l1.write.data := mte.io.l1.write.data
    // L0A
    cubes(0).io.l0a.read.en    := mte.io.l0a.read.en
    cubes(0).io.l0a.read.addr  := mte.io.l0a.read.addr
    mte.io.l0a.read.data       := cubes(0).io.l0a.read.data
    cubes(0).io.l0a.write.en   := mte.io.l0a.write.en
    cubes(0).io.l0a.write.addr := mte.io.l0a.write.addr
    cubes(0).io.l0a.write.data := mte.io.l0a.write.data
    // L0B
    cubes(0).io.l0b.read.en    := mte.io.l0b.read.en
    cubes(0).io.l0b.read.addr  := mte.io.l0b.read.addr
    mte.io.l0b.read.data       := cubes(0).io.l0b.read.data
    cubes(0).io.l0b.write.en   := mte.io.l0b.write.en
    cubes(0).io.l0b.write.addr := mte.io.l0b.write.addr
    cubes(0).io.l0b.write.data := mte.io.l0b.write.data
  } else {
    mte.io.l1.read.data  := 0.U
    mte.io.l0a.read.data := 0.U
    mte.io.l0b.read.data := 0.U
  }

  // MTE external memory port passthrough
  io.mem <> mte.io.mem

  // ---- Command dispatch ----
  io.cmd.ready := true.B  // always accept (buffered by queue upstream)

  when(io.cmd.valid) {
    val cmd = io.cmd.bits
    switch(cmd.target) {
      is(0.U) { // CUBE
        cubes.zipWithIndex.foreach { case (cube, i) =>
          when(cmd.coreIdx === i.U) {
            cube.io.cmd.valid := true.B
            cube.io.cmd.bits  := cmd.cubeCmd
            fixpipes(i).io.config := cmd.fixCfg
          }
        }
      }
      is(1.U) { // VECTOR
        vectors.zipWithIndex.foreach { case (vec, i) =>
          when(cmd.coreIdx === i.U) {
            vec.io.cmd.valid := true.B
            vec.io.cmd.bits  := cmd.vecCmd
          }
        }
      }
      is(2.U) { // MTE
        mte.io.cmd.valid := true.B
        mte.io.cmd.bits  := cmd.dmaCmd
      }
    }
  }

  // ---- Status ----
  io.busy := cubes.map(_.io.busy).reduce(_ || _) ||
             vectors.map(_.io.busy).reduce(_ || _) ||
             mte.io.status.busy

  io.cubeBusy   := VecInit(cubes.map(_.io.busy))
  io.vectorBusy := VecInit(vectors.map(_.io.busy))
  io.mteBusy    := mte.io.status.busy
}
