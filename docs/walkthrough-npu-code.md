# NPU FPGA Prototype — Code Walkthrough

*Date: 2026-04-13*

This document walks through every source file in the NPU FPGA
prototype, explaining what each module does, how the pieces connect, and the
key design decisions that map the reference NPU architecture
to synthesisable Chisel RTL.

For the high-level architecture, scaling decisions, and FPGA resource
estimates see the companion
[design document](design-npu-prototype.md).

---

## Repository layout

```
NPU-FPGA-prototype/
├── build.mill                              # Mill 1.1.5 build definition
├── docs/
│   ├── design-npu-prototype.md            # Architecture & design doc
│   └── walkthrough-npu-code.md            # This file
├── src/
│   ├── main/scala/npu/
│   │   ├── common/
│   │   │   ├── NPUParams.scala            # Configuration & opcode constants
│   │   │   ├── Scratchpad.scala            # SRAM primitives (simple + banked)
│   │   │   └── FP16.scala                  # IEEE 754 FP16 mul, FP32 add
│   │   ├── core/
│   │   │   └── CubeCore.scala              # 16×16×16 matrix engine
│   │   ├── vector/
│   │   │   └── VectorCore.scala            # Element-wise ALU + banked UB
│   │   ├── fixpipe/
│   │   │   └── Fixpipe.scala               # 3-stage post-processing pipeline
│   │   ├── dma/
│   │   │   └── MTE.scala                   # DMA engine (8 transfer paths)
│   │   ├── cluster/
│   │   │   └── NPUCluster.scala            # Top integration (cores + L2)
│   │   └── soc/
│   │       └── NPUSoC.scala                # SoC wrapper + command queue
│   └── test/scala/npu/
│       └── CubeCoreTest.scala              # 6 elaboration/reset smoke tests
├── npu/src -> ../src                       # Symlink for Mill SbtModule convention
└── out/                                    # Mill build artifacts (gitignored)
```

Total: **~1 380 lines of Chisel** across 10 source files.

---

## 1. `common/NPUParams.scala` — Configuration & Constants

**96 lines** | Package `npu.common`

This is the foundation every other module imports.  Three nested case classes
capture the 910C's memory hierarchy:

### `CoreBufferParams`

Per-core SRAM sizes matching the real 910C exactly:

| Buffer | Size   | Role                           |
|--------|--------|--------------------------------|
| L0A    | 64 KB  | CUBE input A tile              |
| L0B    | 64 KB  | CUBE input B tile              |
| L0C    | 128 KB | CUBE output accumulator        |
| L1     | 512 KB | Staging between L2 and L0/UB   |
| UB     | 192 KB | VECTOR unified buffer          |

`ubBanks = 64` — the UB is 64-way banked for parallel VECTOR access.

### `CubeTileParams`

The MAC tile dimensions: **M = 16, N = 16, K = 16**.  One CUBE command
processes a 16×16 output tile by iterating over K = 16 reduction steps.
The 16³ = 4 096 MACs per tile match the real CUBE unit.

### `NPUClusterParams`

System-level parameters:

| Parameter     | Prototype | Real 910C |
|---------------|-----------|-----------|
| CUBE cores    | 1         | 20        |
| VECTOR cores  | 2         | 40        |
| L2 cache      | 64 KB*    | 168 MB    |
| Frequency     | 200 MHz   | 1 500 MHz |
| Address width | 32 bit    | 40 bit    |
| Data width    | 256 bit   | 256 bit   |

\* Test default; design doc targets 2 MB for FPGA.

### `NPUConsts.Opcode`

8-bit opcodes grouped by execution unit:

| Range       | Unit    | Examples                       |
|-------------|---------|--------------------------------|
| `0x01`      | CUBE    | MMAD (matrix multiply-add)     |
| `0x10–0x1E` | VECTOR  | VADD, VMUL, VRELU, VSIGMOID…  |
| `0x30–0x37` | MTE/DMA | HBM↔L2, L2↔L1, L1↔L0A/L0B/UB |
| `0x40–0x41` | Fixpipe | FIXPIPE, FIXPIPE_NZ2ND         |
| `0x00/0xFF` | Control | NOP, BARRIER                   |

These opcodes are used in command dispatch by `NPUCluster`.

---

## 2. `common/Scratchpad.scala` — SRAM Primitives

**73 lines** | Package `npu.common`

Two SRAM variants that map to FPGA block RAM.

### `ScratchpadIO`

Defines the port bundle shared by all scratchpads:

```
read:  en (Input), addr (Input)  → data (Output)   // 1-cycle read latency
write: en (Input), addr (Input), data (Input)       // synchronous write
```

Port direction convention: the **consumer** drives `read.en`/`read.addr` and
`write.en`/`write.addr`/`write.data`.  When MTE (as bus master) needs to
access a scratchpad, it uses a `Flipped(ScratchpadIO)` so the directions
reverse.

### `Scratchpad(depth, width)`

Simple single-read / single-write wrapper around `SyncReadMem`.  Used for
L0A, L0B, L0C, and L1 inside `CubeCore`.

### `BankedScratchpad(depth, width, banks=64)`

64 parallel `SyncReadMem` banks for the VECTOR core's UB:

- **Bank select:** `addr % banks` (low address bits)
- **Word address:** `addr / banks` (high address bits)
- **Read latency:** `RegNext(bankSel)` delays the mux select by one cycle to
  match `SyncReadMem`'s 1-cycle read latency

This gives the VECTOR core 64-way parallel access, matching how the real UB
is banked for SIMD throughput.

---

## 3. `common/FP16.scala` — IEEE 754 Arithmetic

**148 lines** | Package `npu.common`

The computational heart.  Two modules implement the FP16×FP16→FP32 MAC
that the CUBE core's 256 multiply-accumulate units use.

### `FP16Mul` — Combinational FP16 × FP16 → FP32

Algorithm:

1. **Extract fields** — sign (bit 15), exponent (bits 14:10, 5-bit),
   mantissa (bits 9:0, 10-bit) from each FP16 input.
2. **Implicit leading 1** — prepend `1` to each mantissa → 11-bit values.
3. **Multiply mantissas** — 11 × 11 = 22-bit product.
4. **Compute exponent** — `aExp + bExp + 97`.  The `+97` is the key trick:
   FP16 bias = 15, FP32 bias = 127, so the combined bias adjustment is
   127 − 15 − 15 = **97**.
5. **Normalise** — if product bit 21 is set, shift right by 1 and increment
   exponent; pad mantissa to 23 bits (FP32 format).
6. **Zero check** — if either input exponent is zero, force output to zero.

### `FP32Add` — FP32 Accumulator Adder

Used after each `FP16Mul` to accumulate partial products:

1. **Exponent comparison** — swap operands so the larger exponent is `big`.
2. **Align mantissas** — right-shift the smaller mantissa by the exponent
   difference.
3. **Add or subtract** — same sign → add; different sign → subtract.
4. **Normalise** — `PriorityEncoder(Reverse(sum))` finds the leading one,
   then left-shift and adjust exponent.
5. **Carry-out** — if the sum exceeds 24 bits, shift right and increment
   exponent.

### Simplifications

No denormal handling, no round-to-nearest-even, no infinity/NaN propagation.
Sufficient for FPGA functional verification; a production design would need
full IEEE 754 compliance.

### `FP16Utils`

Helper `object` with `extract(bits)` and `pack(sign, exp, man)` functions
for field manipulation.

---

## 4. `core/CubeCore.scala` — The Matrix Engine

**217 lines** | Package `npu.core`

This is the most complex module — one 16×16×16 CUBE core implementing the
`C = A × B` matrix multiply.

### Internal SRAMs

| Buffer | Depth × Width     | Purpose             |
|--------|--------------------|---------------------|
| l0a    | 4096 × 256-bit     | A tile (64 KB)      |
| l0b    | 4096 × 256-bit     | B tile (64 KB)      |
| l0c    | 2048 × 256-bit     | Output staging       |
| l1mem  | 2048 × 256-bit     | L1 cache (512 KB)   |

### External DMA ports

`io.l1`, `io.l0a`, `io.l0b` — `ScratchpadIO` ports connected directly to
the internal SRAMs.  MTE reads/writes these to fill input tiles and drain
results.  The wiring:

```scala
io.l0a.read.data := l0a.read(io.l0a.read.addr, io.l0a.read.en)
when(io.l0a.write.en) { l0a.write(io.l0a.write.addr, io.l0a.write.data) }
```

### Compute array

- **256 `FP16Mul` instances** — 16 × 16 grid
- **256 `FP32Add` instances** — one per (m, n) position
- **`accum(m)(n)`** — 16 × 16 register array holding running FP32 sums

### 5-state FSM

#### `sIdle`

Waits for `io.cmd.valid`.  On command, latches L0A/L0B base addresses and
resets all 256 accumulators to `0.U`.

#### `sLoadA` — 16 cycles

Each cycle reads one 256-bit word from L0A (at `baseA + mCount`) into
`aRow(mCount)`.  After 16 cycles, all 16 rows of the A tile are in
registers.  Each `aRow(m)` holds 16 × FP16 = 256 bits.

#### `sLoadB` — 16 cycles

Same pattern: reads `bRow(k)` from L0B.  Each `bRow(k)` holds 16 × FP16 =
256 bits (one row of B).

#### `sCompute` — 16 cycles (k = 0 … 15)

This is the core MAC loop.  Each cycle processes one k-slice across all
256 (m, n) positions simultaneously:

```scala
// Extract A[m][k] — dynamic bit selection via shift
val aShifted = (aRow(m) >> (kCount(3,0) ## 0.U(4.W)))  // shift right by k*16
val aElem    = aShifted(15, 0)                           // low 16 bits = FP16

// Extract B[k][n] — static bit select within the k-th row
val bWord = bRow(kCount(3, 0))
val bElem = bWord(n * 16 + 15, n * 16)
```

Key details:
- `kCount(3,0) ## 0.U(4.W)` concatenates the 4-bit k index with 4 zero
  bits, effectively computing `k * 16` (the bit offset within the 256-bit
  row).
- Chisel doesn't support dynamic range selects (`x(hi, lo)` where hi/lo are
  `UInt`), so we shift right then take the fixed range `(15, 0)`.
- All 256 multipliers fire in parallel; results accumulate via `FP32Add`
  into `accum(m)(n)`.

#### `sDrain` — 32 cycles

Streams the 16 × 16 = 256 FP32 values through a `Decoupled` output to
Fixpipe.  Each 256-bit word carries 8 × FP32 values → 256 / 8 = 32 words
total.  Uses valid/ready handshake; advances `wordCount` only when
`io.l0c_out.ready` is asserted.

### `CubeCommand` and `CubeTileOutput`

- `CubeCommand`: opcode, l0aAddr, l0bAddr — addresses within L0 scratchpads
- `CubeTileOutput`: 256-bit data + row/col indices for Fixpipe routing

---

## 5. `vector/VectorCore.scala` — Element-wise ALU

**161 lines** | Package `npu.vector`

Processes element-wise operations (add, multiply, ReLU) on vectors stored
in the 192 KB unified buffer (UB).

### Unified Buffer

Instantiated as `BankedScratchpad(ubDepth, 256, 64)` — 64-way banked for
SIMD parallelism.

### UB Arbitration

Two access paths compete for the UB:

| Priority | Source      | When             |
|----------|-------------|------------------|
| External | MTE/Fixpipe | Core is idle     |
| Internal | ALU         | Core is executing|

When idle, `io.ub_ext` drives the UB directly.  When executing, the ALU
owns the UB and the external port is blocked (reads return 0).

### 4-phase pipeline

For each 256-bit word in the vector:

1. **sRead0** — issue read for `src0Addr + wordCount`
2. **sRead1** — latch src0 result, issue read for `src1Addr + wordCount`
3. **sCalc**  — latch src1 result, compute ALU result
4. **sWrite** — write result to `dstAddr + wordCount`, advance `wordCount`

Iterates `len` times to process the entire vector.

### ALU operations

| Opcode | Operation | Status       |
|--------|-----------|--------------|
| VRELU  | `max(x, 0)` per FP16 lane | Implemented — checks sign bit (bit 15) of each 16-bit lane |
| VADD   | `src0 + src1` | Placeholder (pass-through) — needs FP16 SIMD adder |
| VMUL   | `src0 × src1` | Placeholder (pass-through) — needs FP16 SIMD multiplier |

VRELU implementation:

```scala
for (i <- 0 until 16) {  // 16 × FP16 lanes per 256-bit word
  val lane = src0Data(i * 16 + 15, i * 16)
  when(lane(15)) { result(i*16+15, i*16) := 0.U }  // negative → zero
  .otherwise     { result(i*16+15, i*16) := lane }
}
```

### `VectorCommand`

Fields: opcode, src0Addr, src1Addr, dstAddr (UB addresses), len (number of
256-bit words), scalar (for broadcast ops), useScalar flag.

---

## 6. `fixpipe/Fixpipe.scala` — Post-processing Pipeline

**137 lines** | Package `npu.fixpipe`

Sits between CubeCore's L0C drain output and the UB or L1, performing
post-processing on CUBE results.

### 3-stage registered pipeline

Each stage has an enable bit in `FixpipeConfig`:

#### Stage 1: Dequant

Placeholder pass-through.  The real 910C performs INT32 → FP16
dequantisation here (multiply by scale factor).

#### Stage 2: Bias

Has a 7 KB bias buffer (`fb`, a `Reg(Vec(...))`).  Currently passes
through.  The real design adds a per-channel FP32 bias to each element.

#### Stage 3: ReLU + NZ→ND

- **ReLU** — implemented: checks bit 31 (FP32 sign bit) of each 32-bit
  lane within the 256-bit word, zeros negative values.
- **NZ→ND** — address-level only.  The real design performs a full
  Fractal-Z to row-major layout conversion; our prototype handles
  addressing but not data reordering.

### Back-pressure

Valid/ready handshake between stages.  Each stage register advances only
when downstream is ready:

```
CubeCore.l0c_out →(Decoupled)→ Stage1 →(valid/ready)→ Stage2 →(valid/ready)→ Stage3 → output
```

### `FixpipeConfig`

Control bundle: `enableDequant`, `enableBias`, `enableRelu`, `nz2nd`,
`outputToUB` (routes output to UB for VECTOR or L1 for memory writeback).

---

## 7. `dma/MTE.scala` — Memory Transfer Engine

**203 lines** | Package `npu.dma`

The DMA controller handling **all** data movement in the NPU — no core
accesses memory directly.

### 8 transfer paths

Selected by `DMACommand.opcode`:

| Opcode | Path       | Direction |
|--------|------------|-----------|
| `0x30` | HBM → L2   | External → shared cache |
| `0x31` | L2 → HBM   | Shared cache → external |
| `0x32` | L2 → L1    | Shared → per-core staging |
| `0x33` | L1 → L2    | Per-core → shared |
| `0x34` | L1 → L0A   | Staging → CUBE input A |
| `0x35` | L1 → L0B   | Staging → CUBE input B |
| `0x36` | L1 → UB    | Staging → VECTOR buffer |
| `0x37` | UB → L1    | VECTOR buffer → staging |

### 4-state FSM

- **sIdle** — waits for `io.cmd.valid`, latches src/dst addresses and
  length, selects read/write port pair based on opcode
- **sRead** — 1 cycle: asserts read enable on the source port
- **sWrite** — 1 cycle: latches read data, asserts write enable on the
  destination port
- **Loop** — increments addresses, decrements remaining count; if count > 0,
  back to sRead; else to sDone
- **sDone** — 1 cycle: deasserts busy, returns to sIdle

Transfers one 256-bit word per 2-cycle read/write pair.

### Port types

**`MemPortIO`** — simplified AXI4 for external memory:
- AR channel (read address): addr, len, valid/ready
- R channel (read data): data, valid/ready
- AW channel (write address): addr, len, valid/ready
- W channel (write data): data, valid/ready

**Internal scratchpad ports** — `Flipped(new ScratchpadIO(...))`:
- `io.l2`, `io.l1`, `io.l0a`, `io.l0b`, `io.ub`
- `Flipped` because MTE is the bus master driving the consumer-side ports

### `DMACommand`

Fields: opcode (8-bit), srcAddr/dstAddr (40-bit), length (32-bit),
nd2nz/nz2nd flags (for Fractal-Z format conversion, not yet implemented).

---

## 8. `cluster/NPUCluster.scala` — Integration

**174 lines** | Package `npu.cluster`

The "NPU die" equivalent — wires all cores, Fixpipe, MTE, and the shared
L2 together.

### Instantiation

```
cubes[0]     : CubeCore
fixpipes[0]  : Fixpipe       (1:1 with CUBE)
vectors[0..1]: VectorCore    (2 instances)
mte          : MTE
l2           : SyncReadMem   (shared L2 cache)
```

### Key connections

#### CUBE → Fixpipe (Decoupled)

```scala
fix.io.in <> cube.io.l0c_out
```

Fixpipe consumes the CUBE core's drain output directly via the valid/ready
handshake.

#### MTE → L2

Direct read/write wiring to the shared `SyncReadMem`:

```scala
mte.io.l2.read.data := l2.read(l2ReadAddr, l2ReadEn)
when(l2WriteEn) { l2.write(l2WriteAddr, l2WriteData) }
```

#### MTE → CubeCore[0] scratchpads

Hardwired connection of MTE's L1/L0A/L0B ports to the first CUBE core:

```scala
cubes(0).io.l1.read.en    := mte.io.l1.read.en
cubes(0).io.l1.read.addr  := mte.io.l1.read.addr
mte.io.l1.read.data       := cubes(0).io.l1.read.data
// ... same for l0a, l0b
```

A real multi-core design would mux across cores using `coreIdx`.

#### Command dispatch

```scala
when(io.cmd.valid) {
  switch(cmd.target) {
    is(0.U) { /* CUBE  — route to cubes(coreIdx) */ }
    is(1.U) { /* VECTOR — route to vectors(coreIdx) */ }
    is(2.U) { /* MTE */ }
  }
}
```

Multi-core indexing uses:

```scala
cubes.zipWithIndex.foreach { case (cube, i) =>
  when(cmd.coreIdx === i.U) {
    cube.io.cmd.valid := true.B
    cube.io.cmd.bits  := cmd.cubeCmd
  }
}
```

This generates parallel `when` blocks — Chisel compiles them to a priority
mux.  We can't index a Scala `Seq` with a Chisel `UInt` directly.

### `NPUCommand`

Union-style command bundle dispatched by the Task Scheduler:
- `target` (8-bit): 0 = CUBE, 1 = VECTOR, 2 = MTE, 3 = FIXPIPE
- `coreIdx` (8-bit): which core instance
- `cubeCmd`, `vecCmd`, `dmaCmd`, `fixCfg`: embedded sub-commands

Wasteful (every command carries all sub-command fields), but simple for
prototyping.

---

## 9. `soc/NPUSoC.scala` — SoC Wrapper

**71 lines** | Package `npu.soc`

Thin top-level shell:

- **`NPUCluster`** — the compute die
- **`Queue(new NPUCommand, 16)`** — 16-deep command FIFO, buffering between
  the host/RISC-V and the cluster's single-command-at-a-time dispatch

External ports:
- `cmdWrite` — `Decoupled(NPUCommand)` for the host to enqueue commands
- `mem` — AXI4 memory port (passed through from MTE)
- `status` — busy flag + queue depth

`NPUSoCMain` object provides a `main` method for Verilog generation:

```scala
object NPUSoCMain extends App {
  chisel3.emitVerilog(new NPUSoC(NPUClusterParams()), ...)
}
```

This is where the RISC-V Task Scheduler (Rocket core) would attach in a
future milestone — replacing the external `cmdWrite` port with a
processor-driven command stream.

---

## 10. `test/CubeCoreTest.scala` — Elaboration Tests

**101 lines** | Package `npu`

Six tests, one per major module, using `chisel3.simulator.EphemeralSimulator`.

### Test pattern (same for all 6)

```scala
simulate(new Module(p)) { c =>
  // 1. Force Chisel elaboration + FIRRTL compilation
  // 2. Explicitly assert reset (EphemeralSimulator doesn't auto-reset)
  c.reset.poke(true.B)
  c.clock.step(1)
  c.reset.poke(false.B)
  // 3. Drive all inputs to safe defaults
  c.io.cmd.valid.poke(false.B)
  // ... all other inputs ...
  // 4. Step 5 cycles
  c.clock.step(5)
  // 5. Verify idle state
  c.io.busy.peek().litValue shouldBe 0
}
```

### Modules tested

| # | Module       | Key assertion         |
|---|--------------|-----------------------|
| 1 | CubeCore     | `busy == 0` after reset |
| 2 | VectorCore   | `busy == 0` after reset |
| 3 | Fixpipe      | `out.valid == 0` after reset |
| 4 | MTE          | `status.busy == 0` after reset |
| 5 | NPUCluster   | `busy == 0` after reset |
| 6 | NPUSoC       | `status.busy == 0` after reset |

All 6 pass.  These are elaboration + reset smoke tests — they verify that
every module compiles through FIRRTL, instantiates correctly, and reaches
a clean idle state.

---

## End-to-end mmad data flow

Putting it all together, a complete matrix multiply follows this command
sequence through the hardware:

```
Host                           NPU
─────                          ───
1. MTE: HBM → L2       ───→   Load A tile from external memory to shared L2
2. MTE: L2 → L1        ───→   Copy A tile from shared L2 to CUBE's L1
3. MTE: L1 → L0A       ───→   Copy A tile from L1 to CUBE's L0A input buffer
4. MTE: HBM → L2 → L1 → L0B   (same 3 steps for B tile)
5. CUBE: MMAD           ───→   sLoadA → sLoadB → sCompute → sDrain
                                (16+16+16+32 = 80 cycles for 16×16×16 tile)
6. Fixpipe              ───→   dequant → bias → ReLU (3 pipeline stages)
7. VECTOR: VRELU/VADD   ───→   Element-wise post-processing on UB
8. MTE: UB → L1 → L2 → HBM    Store result back to external memory
```

Each step is a separate command enqueued via `NPUSoC.cmdWrite`.  The
16-deep command queue allows pipelining: the host can enqueue the next
tile's DMA while the current tile is still computing.

---

## Build system notes

### Mill 1.1.5 (`build.mill`)

```scala
object npu extends SbtModule {
  def mvnDeps = Seq(mvn"org.chipsalliance::chisel:7.3.0")
  def scalacPluginMvnDeps = Seq(mvn"org.chipsalliance:::chisel-plugin:7.3.0")
  object test extends SbtTests with TestModule.ScalaTest {
    def mvnDeps = Seq(mvn"org.scalatest::scalatest:3.2.19")
  }
}
```

Key differences from older Mill versions (e.g. XiangShan's 0.12.15):
- `mvnDeps` / `mvn""` instead of `ivyDeps` / `ivy""`
- `scalacPluginMvnDeps` instead of `scalacPluginIvyDeps`
- `TestModule.ScalaTest` instead of `TestModule.Scalatest`

### SbtModule path convention

Mill's `SbtModule` named `npu` expects sources at `npu/src/`.  A
symlink `npu/src → ../src` allows us to keep the standard
`src/main/scala` layout at the repo root.

### Commands

```bash
mill -i npu.compile    # Compile all sources
mill -i npu.test       # Run all tests (6 elaboration smoke tests)
```

---

## Known issues and TODOs

1. **VectorCore ALU** — VADD/VMUL are placeholders; need real FP16 SIMD
   lanes using `FP16Mul`/`FP32Add` from `FP16.scala`
2. **Fixpipe dequant/bias** — pass-through; need actual INT32→FP16 and
   per-channel bias implementation
3. **MTE multi-core mux** — currently hardwired to `cubes(0)`; needs
   `coreIdx`-based routing for multi-CUBE configurations
4. **RISC-V Task Scheduler** — register-based command interface; should be
   replaced with a Rocket core running firmware
5. **Functional tests** — no end-to-end mmad test yet; need to inject DMA +
   CUBE commands and verify numerical output
6. **Chisel warning W004** — `Dynamic index with width 8 is too wide for
   Vec of size 16` in CubeCore; harmless (wordCount is 8-bit but Vec has
   16 entries)
