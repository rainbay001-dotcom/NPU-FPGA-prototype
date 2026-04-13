# NPU FPGA Prototype — Design Document

**Project:** `NPU-FPGA-prototype`
**Location:** `/Users/ray/Documents/Repo/NPU-FPGA-prototype/`
**Date:** 2026-04-13
**Status:** Skeleton RTL complete — all modules elaborating and simulating. Functional tests pending.

---

## 1. Motivation

The 910C is a high-end AI training NPU.  Its split-architecture design —
software-managed scratchpads, split CUBE/VECTOR cores, explicit DMA, Fractal Z
memory layout — is fundamentally different from GPUs and CPUs, making it a
compelling target for hardware exploration.

We already have:
- A verified two-core XiangShan RISC-V design (Chisel, CHI coherence)
- Full 910C microarchitecture documentation extracted from CANN 8.5.0 configs
- A cycle-accurate software simulator (`xPU-simulator`)
- Access to real 910C hardware for validation

**Goal:** Build a synthesizable, FPGA-prototypable RTL model of a scaled-down
910C.  Use the XiangShan Chisel ecosystem for the control plane (RISC-V
replacing the ARM Task Scheduler), and build the NPU compute datapath from
scratch.

### Why not just modify XiangShan?

| Aspect | XiangShan (CPU) | 910C (NPU) |
|--------|-----------------|-------------|
| Compute | Superscalar ALU/FPU pipeline | Fixed 16x16x16 MAC array + VECTOR ALU |
| Memory | Transparent cache hierarchy | Software-managed scratchpads (L0/L1/UB) |
| Data movement | Hardware prefetch + coherence | Explicit MTE DMA engine |
| Control | Branch prediction, OoO, speculation | Firmware Task Scheduler dispatches kernels |

These are different machines.  XiangShan contributes the **SoC infrastructure**
(Chisel toolchain, CHI interconnect, Rocket RISC-V cores, build/verify flow),
not the compute datapath.

---

## 2. Architecture Overview

### 2.1 Target: 910C split-mode architecture

The 910C has 20 CUBE cores and 40 VECTOR cores that execute **independently**
(split mode).  CUBE handles matrix multiply (`mmad`); VECTOR handles
element-wise ops (add, mul, exp, relu, etc.).  A Fixpipe pipeline bridges the
two: CUBE output → post-processing → VECTOR input.

All memory except shared L2 is **software-managed scratchpad**.  The programmer
(CANN compiler) explicitly schedules DMA transfers via the MTE engine.

### 2.2 FPGA prototype configuration

Full 910C is too large for any FPGA.  Our prototype scales down:

| Parameter | 910C (9362) | FPGA Prototype | Ratio |
|-----------|-------------|----------------|-------|
| CUBE cores | 20 | 1 | 1/20 |
| VECTOR cores | 40 | 2 | 1/20 |
| Frequency | 1500 MHz | ~200 MHz | 1/7.5 |
| L2 cache | 168 MB | 2 MB | 1/84 |
| HBM | 64 GB, 1638 GB/s | DDR4 or HBM2 (FPGA-dependent) | — |
| Per-core buffers | Same sizes | Same sizes | 1:1 |

Per-core buffer sizes are kept identical to 910C so kernel code runs unmodified.

### 2.3 Block diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        NPUSoC                                       │
│                                                                     │
│   ┌──────────────────┐                                              │
│   │  Command Queue    │ ◄── cmdWrite (from RISC-V or testbench)     │
│   │  (depth 16)       │                                              │
│   └────────┬─────────┘                                              │
│            │ NPUCommand                                              │
│            ▼                                                         │
│   ┌────────────────────────────────────────────────────────────┐    │
│   │                     NPUCluster                              │    │
│   │                                                             │    │
│   │   ┌───────────────┐    ┌───────────────┐                   │    │
│   │   │  CubeCore[0]  │    │ VectorCore[0] │                   │    │
│   │   │               │    │               │                   │    │
│   │   │  ┌─────────┐  │    │  ┌─────────┐  │   ┌────────────┐ │    │
│   │   │  │ L0A 64K │  │    │  │ UB 192K │  │   │VectorCore[1]│ │    │
│   │   │  │ L0B 64K │  │    │  │ (64 bnk)│  │   │  (same)    │ │    │
│   │   │  ├─────────┤  │    │  ├─────────┤  │   └────────────┘ │    │
│   │   │  │16x16x16 │  │    │  │VECTOR   │  │                   │    │
│   │   │  │MAC Array │  │    │  │ALU      │  │                   │    │
│   │   │  │(256 FP16 │  │    │  │vadd/vmul│  │                   │    │
│   │   │  │ muls)    │  │    │  │vrelu/...│  │                   │    │
│   │   │  ├─────────┤  │    │  └─────────┘  │                   │    │
│   │   │  │ L0C 128K│  │    └───────────────┘                   │    │
│   │   │  ├─────────┤  │                                         │    │
│   │   │  │ L1 512K │  │                                         │    │
│   │   │  └─────────┘  │                                         │    │
│   │   └───────┬───────┘                                         │    │
│   │           │ L0C drain                                        │    │
│   │           ▼                                                  │    │
│   │   ┌───────────────┐                                         │    │
│   │   │   Fixpipe     │  dequant → bias → ReLU → NZ→ND         │    │
│   │   └───────┬───────┘                                         │    │
│   │           │ → L1 or → UB                                     │    │
│   │           ▼                                                  │    │
│   │   ┌───────────────┐         ┌────────────────────────────┐  │    │
│   │   │   MTE (DMA)   │ ◄─────►│  Shared L2 (2 MB SRAM)    │  │    │
│   │   │               │         └────────────────────────────┘  │    │
│   │   │ L2↔L1↔L0A/B  │                                         │    │
│   │   │ L1↔UB         │                                         │    │
│   │   │ HBM↔L2        │                                         │    │
│   │   └───────┬───────┘                                         │    │
│   │           │ AXI4 mem port                                    │    │
│   └───────────┼─────────────────────────────────────────────────┘    │
│               ▼                                                      │
│        ┌──────────────┐                                              │
│        │  DDR / HBM   │  (external, FPGA-dependent)                  │
│        └──────────────┘                                              │
└─────────────────────────────────────────────────────────────────────┘
```

---

## 3. Module Design

### 3.1 Parameter system (`common/NPUParams.scala`, 96 lines)

Three nested case classes configure the entire design:

```
NPUClusterParams
  ├── cubeCores: Int = 1        (910C: 20)
  ├── vectorCores: Int = 2      (910C: 40)
  ├── l2SizeKB: Int = 2048      (910C: 168 MB)
  ├── freqMHz: Int = 200        (910C: 1500)
  ├── dataWidth: Int = 256      (AXI bus width)
  ├── addrWidth: Int = 40
  ├── CoreBufferParams
  │     ├── l0aSize  = 64 KB
  │     ├── l0bSize  = 64 KB
  │     ├── l0cSize  = 128 KB
  │     ├── l1Size   = 512 KB
  │     ├── ubSize   = 192 KB
  │     ├── ubBanks  = 64
  │     ├── ubGroups = 16
  │     ├── fbSizes  = [2K, 1K, 2K, 2K]  (Fixpipe buffers)
  │     └── btSize   = 1 KB               (bias table)
  └── CubeTileParams
        ├── m = 16
        ├── k = 16    (32 for INT8, 64 for INT4)
        └── n = 16
```

All buffer sizes match the reference NPU config exactly.
`NPUClusterParams` is threaded through every module as `val p`.

**Opcode encoding** (`NPUConsts.Opcode`): 8-bit opcodes in four ranges:
- `0x01`: CUBE (`MMAD`)
- `0x10–0x1E`: VECTOR ALU (`VADD`, `VMUL`, `VRELU`, etc.)
- `0x30–0x37`: MTE DMA transfers
- `0x40–0x41`: Fixpipe data paths
- `0x00`/`0xFF`: control (NOP, BARRIER)

### 3.2 Scratchpad SRAM (`common/Scratchpad.scala`, 73 lines)

Two variants used throughout:

**`Scratchpad`** — single-read, single-write port backed by `SyncReadMem`.
Used for L0A, L0B, L0C, L1.  Parameterized by depth and bit-width (typically
256-bit = 32 bytes per word).

**`BankedScratchpad`** — 64-bank structure matching 910C UB.  Bank selection
via low bits of address; each bank is an independent `SyncReadMem`.  Enables
conflict-free access patterns when different VECTOR lanes target different banks.

Port interface (`ScratchpadIO`):
```
read.en   : Input(Bool)
read.addr : Input(UInt)
read.data : Output(UInt)
write.en  : Input(Bool)
write.addr: Input(UInt)
write.data: Input(UInt)
```

This convention means the **user** of the scratchpad drives `en/addr/data`;
the scratchpad provides `read.data`.  When MTE (which is the master) connects
to a scratchpad port, the port must be `Flipped`.

### 3.3 FP16 arithmetic (`common/FP16.scala`, 148 lines)

Two combinational (single-cycle) modules:

**`FP16Mul`** — IEEE 754 half-precision multiply → FP32 result.
- Extracts sign, 5-bit exponent, 10-bit mantissa from each operand
- Adds implicit leading 1 for normalized numbers
- 11-bit × 11-bit = 22-bit product
- Exponent: `aExp + bExp + 97` (combined bias adjustment: FP16→FP32)
- Normalizes based on product MSB; zero-checks for special cases
- Output: 32-bit IEEE 754 single-precision

**`FP32Add`** — IEEE 754 single-precision adder for accumulation.
- Aligns mantissas by exponent difference
- Adds or subtracts based on sign comparison
- Normalizes via leading-zero count
- Handles carry-out from addition
- Simplified: no full round-to-nearest-even (acceptable for prototype)

These are instantiated 256 times (16×16) in each CUBE core's MAC array.

### 3.4 CUBE Core (`core/CubeCore.scala`, 217 lines)

The CUBE core is the matrix multiply engine.  One core computes one 16×16×16
`mmad` tile — C[m][n] += A[m][k] × B[k][n] — accumulating across K slices.

**Internal SRAMs:**
- `l0a`: 64 KB `SyncReadMem` — holds activation tile A
- `l0b`: 64 KB `SyncReadMem` — holds weight tile B (Fractal Z format)
- `l0c`: 128 KB `SyncReadMem` — output accumulator
- `l1mem`: 512 KB `SyncReadMem` — double-buffer region for MTE prefetch

All four SRAMs expose external DMA ports (`io.l1`, `io.l0a`, `io.l0b`) for
MTE to load data.

**MAC array:** 16×16 = 256 instances of `FP16Mul` + `FP32Add`.
Accumulator state held in `accumRegs`: a `Vec[Vec[UInt(32.W)]]` of 16×16 FP32
registers.

**State machine (5 states):**

```
  sIdle ──cmd.valid──► sLoadA ──M words──► sLoadB ──K words──► sCompute
                                                                  │
                                                            16 k-slices
                                                                  │
                                                                  ▼
                                                               sDrain
                                                            32 words out
                                                                  │
                                                                  ▼
                                                               sIdle
```

1. **sIdle**: Accept command. Optionally clear accumulators (`accum=false`).
2. **sLoadA**: Read M=16 × 256-bit words from L0A into `aRow` registers.
3. **sLoadB**: Read K=16 × 256-bit words from L0B into `bRow` registers.
4. **sCompute**: 16 cycles. Each cycle processes one k-slice: for all (m,n),
   extract `a[m][k]` and `b[k][n]` as FP16, multiply, accumulate into
   `accumRegs[m][n]`.  Dynamic bit selection via shift: `aRow(m) >> (k*16)`.
5. **sDrain**: Stream 32 × 256-bit words (= 256 FP32 values = one 16×16 tile)
   to Fixpipe via `io.l0c_out` (Decoupled).

**Cycle count per tile:** 16 (load A) + 16 (load B) + 16 (compute) + 32
(drain) = **80 cycles**.  The 910C does this in ~1 cycle via pipelining; our
prototype is iterative.

### 3.5 VECTOR Core (`vector/VectorCore.scala`, 161 lines)

Element-wise ALU operating on the 192 KB Unified Buffer (UB).

**UB:** Instantiates `BankedScratchpad(nBanks=64)`.  External port
(`io.ub_ext`) allows MTE or Fixpipe to load/store data when the VECTOR core is
idle.  When busy, internal ALU has exclusive access.

**ALU pipeline (4 phases per element-word):**
```
  sRead0 → sRead1 → sCalc → sWrite → sRead0 → ... (repeat for len words)
```
1. **sRead0**: Read src0 word from UB
2. **sRead1**: Capture src0, read src1 word from UB
3. **sCalc**: Capture src1, compute ALU result
4. **sWrite**: Write result to dst in UB, advance to next word

**Supported operations:**
- `VADD`: 16-wide FP16 vector add (placeholder — needs real SIMD)
- `VMUL`: 16-wide FP16 vector multiply (placeholder)
- `VRELU`: 16-wide FP16 ReLU — zero out elements with sign bit set (implemented)

Each 256-bit word contains 16 packed FP16 values.  The ReLU implementation is
correct (checks bit 15 of each 16-bit lane); add/mul are structural
placeholders that need 16× `FP16Add`/`FP16Mul` instantiation.

### 3.6 Fixpipe (`fixpipe/Fixpipe.scala`, 137 lines)

Fixed-function post-processing pipeline between CUBE L0C output and L1/UB.
Matches the 910C Fixpipe stages.

**Configuration** (`FixpipeConfig`):
- `enableDequant`: INT32→FP16/FP32
- `enableBias`: add per-channel bias from fb buffers
- `enableRelu`: ReLU activation
- `enableNZ2ND`: Fractal Z → row-major format conversion
- `outputToUB`: route output to VECTOR UB (vs. L1)

**Pipeline (3 registered stages):**
```
  Input (from CUBE L0C)
    │
    ▼
  Stage 1: Dequant (or passthrough)
    │
    ▼
  Stage 2: Bias Add (from fb buffers, 7 KB total)
    │
    ▼
  Stage 3: ReLU + NZ→ND
    │
    ▼
  Output (to L1 or UB, selected by config.outputToUB)
```

**Bias buffers:** 7 KB `SyncReadMem` (fb0:2K + fb1:1K + fb2:2K + fb3:2K)
loaded via separate MTE path (not yet wired).

ReLU is implemented: checks sign bit of each 32-bit lane (FP32 output from
CUBE).  Dequant and bias add are structural placeholders.

### 3.7 MTE — Memory Transfer Engine (`dma/MTE.scala`, 203 lines)

Explicit DMA engine for all data movement.  This is a key differentiator from
GPU architectures — there is **no hardware cache coherence** between scratchpad
levels.  All movement is software-scheduled.

**Supported transfer paths:**
| Opcode | Source | Destination |
|--------|--------|-------------|
| `DMA_HBM_TO_L2` | External memory (AXI4) | Shared L2 |
| `DMA_L2_TO_L1` | Shared L2 | CubeCore L1 |
| `DMA_L1_TO_L0A` | CubeCore L1 | CubeCore L0A |
| `DMA_L1_TO_L0B` | CubeCore L1 | CubeCore L0B |
| `DMA_L0C_TO_L1` | CubeCore L0C | CubeCore L1 |
| `DMA_L1_TO_UB` | CubeCore L1 | VectorCore UB |
| `DMA_UB_TO_L2` | VectorCore UB | Shared L2 |
| `DMA_L2_TO_HBM` | Shared L2 | External memory |

**State machine:** `sIdle → sRead → sWrite → sRead → ... → sDone → sIdle`.
Transfers one 256-bit word per 2-cycle read/write pair.  Handles variable-length
transfers (`length` field in command).

**External memory port** (`MemPortIO`): simplified AXI4 with AR/R/AW/W
channels.  Directly passed through from NPUCluster to SoC top.

**Port directions:** MTE is the master — all scratchpad ports are `Flipped(ScratchpadIO)`,
meaning MTE drives read.en/addr and write.en/addr/data.

### 3.8 NPU Cluster (`cluster/NPUCluster.scala`, 174 lines)

Top-level integration — the "NPU die" equivalent.

**Instantiation:**
- `cubes`: `Seq[CubeCore]` (parametric, default 1)
- `fixpipes`: `Seq[Fixpipe]` (1:1 with CUBE cores)
- `vectors`: `Seq[VectorCore]` (parametric, default 2)
- `mte`: single `MTE` instance
- `l2`: shared `SyncReadMem` (2 MB default)

**Wiring:**
- Each CubeCore's `l0c_out` connects to its paired Fixpipe's `in` (Decoupled)
- MTE scratchpad ports connect to CubeCore[0]'s L1/L0A/L0B (hardwired for
  prototype; real design would mux across cores)
- MTE L2 port connects to the shared L2 `SyncReadMem`
- MTE external memory port passes through to `io.mem`

**Command dispatch:** NPUCommand has a `target` field (0=CUBE, 1=VECTOR, 2=MTE)
and `coreIdx`.  Cluster decodes `target` in a `switch` and routes the
embedded sub-command to the appropriate core using `when(coreIdx === i.U)`.

### 3.9 SoC Top (`soc/NPUSoC.scala`, 71 lines)

Thin wrapper: NPUCluster + 16-deep command `Queue`.

```
io.cmdWrite ──► Queue(16) ──► NPUCluster.cmd
io.mem      ◄──────────────── NPUCluster.mem
io.status   ◄──────────────── NPUCluster.{busy, cubeBusy, vectorBusy, mteBusy}
```

The command queue decouples the RISC-V issuer from NPU back-pressure.  In the
current prototype, `io.cmdWrite` is driven directly from the testbench; Rocket
RISC-V integration is the next milestone.

---

## 4. Memory Hierarchy Detail

Matching the 910C software-managed scratchpad design:

```
                        ┌──────────────────────────────┐
                        │   CUBE Core (private)        │
                        │                              │
  ┌───────┐   512 GB/s  │  ┌──────┐  ┌──────┐         │
  │ L0A   │ ◄────────── │  │      │  │      │         │
  │ 64 KB │             │  │  L1  │  │ L0C  │         │
  └───┬───┘   256 GB/s  │  │512 KB│  │128 KB│         │
  ┌───┴───┐ ◄────────── │  │      │  │      │         │
  │ L0B   │             │  └──┬───┘  └──┬───┘         │
  │ 64 KB │             │     │  MTE     │ Fixpipe     │
  └───┬───┘             └─────┼──────────┼─────────────┘
      │                       │          │
      ▼  MAC Array            │          │ 256 GB/s
   16×16×16                   │          ▼
      │                       │    ┌──────────────┐
      ▼                       │    │ VECTOR Core   │
   L0C (accum)                │    │ UB 192 KB     │
                              │    │ (64 banks)    │
                              │    └──────┬───────┘
                              │           │
              110/86 GB/s     │           │ 64 GB/s
              ┌───────────────┼───────────┘
              ▼               ▼
        ┌──────────────────────────┐
        │  Shared L2   (2 MB)     │
        │  PIPT, 64 pages          │
        └────────────┬─────────────┘
                     │ 1638 GB/s (910C) / FPGA DDR BW
                     ▼
              ┌──────────────┐
              │  DDR / HBM   │
              └──────────────┘
```

All bandwidth numbers above are from the 910C `[AICoreMemoryRates]` config.
On FPGA these will be lower (limited by fabric routing), but the **ratios**
are what matter for kernel tiling decisions.

---

## 5. Dataflow: End-to-End mmad Example

A single matrix multiply tile on this prototype:

```
Step  Engine   Action                              Cycles
────  ──────   ──────                              ──────
 1    MTE      DMA_HBM_TO_L2: load A,B tiles      ~len×2
 2    MTE      DMA_L2_TO_L1:  A tile → L1          ~16×2
 3    MTE      DMA_L1_TO_L0A: A tile → L0A          ~16×2
 4    MTE      DMA_L2_TO_L1:  B tile → L1          ~16×2
 5    MTE      DMA_L1_TO_L0B: B tile → L0B          ~16×2
 6    CUBE     MMAD: load A/B rows, 16 k-slices     ~48
 7    CUBE     drain L0C → Fixpipe                   ~32
 8    Fixpipe  dequant → bias → ReLU → output        ~3 pipeline stages
 9    MTE      DMA_L1_TO_UB or DMA_UB_TO_L2          ~32×2
```

With double-buffering (not yet implemented), steps 1-5 overlap with 6-9 for
the next tile, hiding DMA latency.

---

## 6. Build System

**Toolchain:** Mill 1.1.5 + Scala 2.13.17 + Chisel 7.3.0

**Key Mill 1.1.5 differences from XiangShan's Mill 0.12.15:**
- `ivyDeps` → `mvnDeps`
- `scalacPluginIvyDeps` → `scalacPluginMvnDeps`
- `ivy"..."` → `mvn"..."`
- `$ivy` imports removed (not needed for simple projects)

**Build commands:**
```bash
cd /Users/ray/Documents/Repo/NPU-FPGA-prototype
mill -i npu.compile       # compile all 9 sources (~6s)
mill -i npu.test           # run 6 elaboration tests (~20s)
```

**Directory layout:**
```
NPU-FPGA-prototype/
├── build.mill                          # Mill build definition
├── npu/ → src (symlink)                # SbtModule path convention
├── docs/
│   └── design-npu-prototype.md         # this document
└── src/
    ├── main/scala/npu/
    │   ├── common/
    │   │   ├── NPUParams.scala         # configuration (96 lines)
    │   │   ├── Scratchpad.scala        # SRAM modules (73 lines)
    │   │   └── FP16.scala             # FP16 mul + FP32 add (148 lines)
    │   ├── core/
    │   │   └── CubeCore.scala         # MAC array + scratchpads (217 lines)
    │   ├── vector/
    │   │   └── VectorCore.scala       # VECTOR ALU + UB (161 lines)
    │   ├── fixpipe/
    │   │   └── Fixpipe.scala          # post-processing pipeline (137 lines)
    │   ├── dma/
    │   │   └── MTE.scala              # DMA engine (203 lines)
    │   ├── cluster/
    │   │   └── NPUCluster.scala       # top integration (174 lines)
    │   └── soc/
    │       └── NPUSoC.scala           # SoC wrapper (71 lines)
    └── test/scala/npu/
        └── CubeCoreTest.scala         # 6 elaboration tests (101 lines)

Total: 1,381 lines of Scala/Chisel
```

---

## 7. Test Status

All 6 tests pass (`mill -i npu.test`):

| Test | Module | What it verifies |
|------|--------|-----------------|
| CubeCore | `CubeCore` | Elaborates, resets to idle (busy=0) |
| VectorCore | `VectorCore` | Elaborates, resets to idle |
| Fixpipe | `Fixpipe` | Elaborates, output valid=0 after reset |
| MTE | `MTE` | Elaborates, status.busy=0 after reset |
| NPUCluster | `NPUCluster` | Full cluster elaborates, all cores idle |
| NPUSoC | `NPUSoC` | SoC with command queue, idle after reset |

These are **elaboration/reset** tests only.  No functional (data-through) tests
yet.

---

## 8. Known Limitations and TODOs

### 8.1 Functional gaps

| Item | Status | Priority |
|------|--------|----------|
| VectorCore ALU (vadd, vmul) | Placeholder — returns input unchanged | High |
| Fixpipe dequant stage | Placeholder — passthrough | Medium |
| Fixpipe bias add | Placeholder — no actual FP32 vector add | Medium |
| NZ→ND format conversion | Address-level only, not tested | Low |
| MTE ND↔NZ on-the-fly conversion | Not implemented | Low |
| Double-buffering in L1 | FSM is sequential, no overlap | Medium |
| Multi-core MTE mux | Hardwired to CubeCore[0] | Low (1-core prototype) |
| FP32 adder rounding | Simplified, no round-to-nearest-even | Low |

### 8.2 Missing features

| Feature | Description |
|---------|-------------|
| Rocket RISC-V control core | Currently testbench-driven; needs Rocket integration via AXI4-MMIO |
| INT8/BF16 mmad variants | Only FP16×FP16→FP32 implemented |
| Sparsity (2:4) | 910C supports it; not in prototype |
| SMMU/TLB | MTE uses physical addresses only |
| SDMA (inter-chip) | Not applicable to single-chip FPGA prototype |
| Functional tests | No end-to-end mmad verification |
| Verilog generation | `NPUSoCMain` entry point exists but untested |

### 8.3 Chisel warnings

Three `[W004]` warnings about Vec index width in CubeCore (dynamic `wordCount`
indexing `aRow`/`bRow` of size 16 with 8-bit counter).  Functionally correct;
fix by truncating to 4 bits.

---

## 9. FPGA Target Considerations

### 9.1 Resource estimate (1 CUBE + 2 VECTOR)

| Resource | Estimate | Notes |
|----------|----------|-------|
| LUTs | ~200K–300K | 256 FP16 muls dominate |
| FFs | ~100K–150K | Accumulator regs, pipeline stages |
| BRAM (36K) | ~150–200 | L0A(2) + L0B(2) + L0C(4) + L1(16) + UB×2(12) + L2(64) |
| DSP | ~256–512 | FP16 mul can map to DSP48E2 |

### 9.2 Candidate boards

| Board | LUTs | BRAM | DSP | HBM | Notes |
|-------|------|------|-----|-----|-------|
| Alveo U200 | 1.2M | 2160 | 6840 | No (DDR4) | Adequate, no HBM |
| Alveo U280 | 1.3M | 2016 | 9024 | **8 GB HBM2** | Best fit — real HBM |
| VCU128 (VU37P) | 1.3M | 2016 | 9024 | 8 GB HBM2 | Dev board variant |

The **U280** is the ideal target: it has HBM2 so we can prototype the actual
memory subsystem rather than substituting DDR4.

### 9.3 FPGA clock

Target 200 MHz.  The critical path is likely the FP16 multiplier or the FP32
adder (both combinational, single-cycle).  If timing fails, pipeline the
multiplier to 2 cycles (split mantissa multiply from exponent/normalization).

---

## 10. Relationship to Other Projects

| Project | Relationship |
|---------|-------------|
| **XiangShan** (`/Repo/XiangShan`) | Chisel toolchain, Rocket cores for control plane, CHI interconnect |
| **xPU-simulator** (`/Repo/xPU-simulator`) | Cycle-accurate software model of same architecture; validation oracle |
| **910C hardware** (NPU server) | Ground truth for behavior and performance; profiling via msprof |
| **Triton-Ascend** (`/Repo/triton`) | Kernel compiler targeting same ISA; test kernel source |
| **NPU ISA** (external) | Canonical instruction set documentation |
