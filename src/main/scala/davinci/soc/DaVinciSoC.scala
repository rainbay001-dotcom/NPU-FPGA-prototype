// Da Vinci SoC Top — RISC-V control core + NPU cluster
// The RISC-V core replaces the ARM-based Task Scheduler and AI CPUs.
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
package davinci.soc

import chisel3._
import chisel3.util._
import davinci.common._
import davinci.cluster._
import davinci.dma.MemPortIO

class DaVinciSoCIO(val p: NPUClusterParams) extends Bundle {
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
}

class DaVinciSoC(val p: NPUClusterParams = NPUClusterParams()) extends Module {
  val io = IO(new DaVinciSoCIO(p))

  // NPU cluster
  val npu = Module(new NPUCluster(p))

  // Command queue (depth 16): buffers commands from RISC-V
  val cmdQueue = Module(new Queue(new NPUCommand, 16))
  cmdQueue.io.enq <> io.cmdWrite
  npu.io.cmd <> cmdQueue.io.deq

  // External memory
  io.mem <> npu.io.mem

  // Status
  io.status.busy       := npu.io.busy
  io.status.cubeBusy   := npu.io.cubeBusy
  io.status.vectorBusy := npu.io.vectorBusy
  io.status.mteBusy    := npu.io.mteBusy
}

// Verilog generation entry point
object DaVinciSoCMain extends App {
  val p = NPUClusterParams(
    cubeCores   = 1,
    vectorCores = 2,
    l2SizeKB    = 2 * 1024,  // 2 MB L2
    freqMHz     = 200
  )
  chisel3.emitVerilog(
    new DaVinciSoC(p),
    Array("--target-dir", "build")
  )
}
