# NPU-FPGA-prototype

FPGA prototype of a scaled-down split-architecture NPU, written in Chisel 7.3.0.

## Architecture

The prototype implements a minimal but functionally complete NPU datapath:

| Unit | Prototype | Reference NPU | Description |
|------|-----------|---------------|-------------|
| CUBE cores | 1 | 20 | 16×16×16 FP16×FP16→FP32 MAC array |
| VECTOR cores | 2 | 40 | Element-wise ALU + 192 KB banked unified buffer |
| Fixpipe | 1 | 20 | 3-stage post-processing (dequant → bias → ReLU) |
| MTE (DMA) | 1 | 1 | 8-path data mover (HBM↔L2↔L1↔L0/UB) |
| Shared L2 | 64 KB* | 168 MB | SyncReadMem, shared by all cores |
| Command queue | 16-deep | ARM TSched | Register-based (RISC-V planned) |

\* Test default; FPGA target is 2 MB on Alveo U280.

Per-core buffer sizes match the reference NPU exactly (L0A=64K, L0B=64K, L0C=128K, L1=512K, UB=192K) so kernel code is portable.

### Block diagram

```
                    ┌─────────────────────────────────────────┐
                    │                NPUSoC                    │
                    │  ┌──────────┐                            │
  cmdWrite ────────►│  │ Queue(16)│──► NPUCluster              │
                    │  └──────────┘     │                      │
                    │    ┌──────────────┼──────────────┐       │
                    │    │  Shared L2   │              │       │
                    │    └──────┬───────┘              │       │
                    │           │                      │       │
                    │    ┌──────┴──────┐    ┌──────────┴──┐   │
                    │    │    MTE      │    │  Command     │   │
                    │    │  (DMA)      │    │  Dispatch    │   │
                    │    └──┬───┬───┬──┘    └──┬───┬──────┘   │
                    │       │   │   │          │   │           │
                    │    ┌──▼─┐ │ ┌─▼──────┐  │   │           │
                    │    │L0A │ │ │L0B     │  │   │           │
                    │    └──┬─┘ │ └──┬─────┘  │   │           │
                    │       │   │    │        │   │           │
                    │    ┌──▼───▼────▼──┐  ┌──▼───▼──┐       │
                    │    │  CUBE Core    │  │ VECTOR  │×2     │
                    │    │ 16×16×16 MAC  │  │  Core   │       │
                    │    └──────┬────────┘  └─────────┘       │
                    │           │                              │
                    │    ┌──────▼────────┐                     │
                    │    │   Fixpipe     │                     │
                    │    │ deq→bias→relu │                     │
                    │    └───────────────┘                     │
                    │                                mem ─────►│ AXI4
                    └─────────────────────────────────────────┘
```

## Repository layout

```
├── build.mill                              # Mill 1.1.5 build definition
├── docs/
│   ├── design-npu-prototype.md             # Architecture & design document
│   └── walkthrough-npu-code.md             # Detailed code walkthrough
├── src/
│   ├── main/scala/npu/
│   │   ├── common/                         # NPUParams, Scratchpad, FP16
│   │   ├── core/                           # CubeCore (MAC array + FSM)
│   │   ├── vector/                         # VectorCore (ALU + banked UB)
│   │   ├── fixpipe/                        # Fixpipe (3-stage pipeline)
│   │   ├── dma/                            # MTE (DMA engine, 8 paths)
│   │   ├── cluster/                        # NPUCluster (integration)
│   │   └── soc/                            # NPUSoC (top + cmd queue)
│   └── test/scala/npu/
│       └── CubeCoreTest.scala              # 6 elaboration/reset tests
└── npu/src -> ../src                       # Symlink for Mill SbtModule
```

~1 380 lines of Chisel across 10 source files.

## Building

Requires [Mill](https://mill-build.org/) 1.1.5+ and JDK 11+.

```bash
# Compile
mill -i npu.compile

# Run all tests (6 elaboration + reset smoke tests)
mill -i npu.test

# Generate Verilog
mill -i npu.runMain npu.soc.NPUSoCMain
```

## Status

- All 6 elaboration/simulation tests passing
- CubeCore: full 16×16×16 MAC with IEEE 754 FP16 multiply + FP32 accumulate
- VectorCore: VRELU implemented; VADD/VMUL are placeholders (need FP16 SIMD)
- Fixpipe: ReLU implemented; dequant/bias are placeholders
- MTE: all 8 DMA paths wired; transfers 256-bit words
- No end-to-end functional tests yet
- No RISC-V Task Scheduler yet (planned: Rocket core)

## FPGA target

Alveo U280 (Virtex UltraScale+ XCU280) — has HBM2 for realistic external memory bandwidth.

## Documentation

- [Design Document](docs/design-npu-prototype.md) — architecture, scaling decisions, resource estimates
- [Code Walkthrough](docs/walkthrough-npu-code.md) — per-file explanation of the RTL
