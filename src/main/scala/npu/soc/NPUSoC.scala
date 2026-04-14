// NPU SoC Top — RISC-V control core + NPU cluster
// The RISC-V core replaces the ARM-based Task Scheduler.
// It issues NPU commands via memory-mapped registers.
//
// Memory map:
//   0x0000_0000 - 0x0FFF_FFFF  DDR/HBM (via AXI, shared with NPU)
//   0x1000_0000 - 0x1000_00FF  NPU command registers
//   0x1000_0100 - 0x1000_01FF  NPU status registers
//   0x1000_0200 - 0x1000_0FFF  L2 debug window
//
// For the FPGA prototype, the RISC-V core is a simple RV32I/RV64I
// that boots from a ROM containing the kernel dispatch firmware.
// Full Rocket integration comes later; this version uses a
// register-based command interface that can be driven from
// simulation testbench or from an actual RISC-V core.
package npu.soc

import chisel3._
import chisel3.util._
import npu.common._
import npu.cluster._
import npu.dma.MemPortIO

class NPUSoCIO(val p: NPUClusterParams) extends Bundle {
  // External memory port
  val mem = new MemPortIO(p.addrWidth, p.dataWidth)

  // Register-based command interface (memory-mapped from RISC-V or testbench)
  val cmdWrite = Flipped(Decoupled(new NPUCommand))
  val status   = Output(new Bundle {
    val busy       = Bool()
    val cubeBusy   = Vec(p.cubeCores, Bool())
    val vectorBusy = Vec(p.vectorCores, Bool())
    val mteBusy    = Bool()
  })

  // Debug: Fixpipe output (for end-to-end testing)
  val fixOut = Decoupled(new Bundle {
    val data = UInt(256.W)
    val last = Bool()
  })
}

class NPUSoC(val p: NPUClusterParams = NPUClusterParams()) extends Module {
  val io = IO(new NPUSoCIO(p))

  // NPU cluster
  val cluster = Module(new NPUCluster(p))

  // Command queue (depth 16): buffers commands from RISC-V
  val cmdQueue = Module(new Queue(new NPUCommand, 16))
  cmdQueue.io.enq <> io.cmdWrite
  cluster.io.cmd <> cmdQueue.io.deq

  // External memory
  io.mem <> cluster.io.mem

  // Debug fixpipe output
  io.fixOut <> cluster.io.fixOut

  // Status
  io.status.busy       := cluster.io.busy
  io.status.cubeBusy   := cluster.io.cubeBusy
  io.status.vectorBusy := cluster.io.vectorBusy
  io.status.mteBusy    := cluster.io.mteBusy
}

// Verilog generation entry point
object NPUSoCMain extends App {
  val p = NPUClusterParams(
    cubeCores   = 1,
    vectorCores = 2,
    l2SizeKB    = 2 * 1024,  // 2 MB L2
    freqMHz     = 200
  )
  chisel3.emitVerilog(
    new NPUSoC(p),
    Array("--target-dir", "build")
  )
}
