---
inclusion: always
---

# COUBES Project — Steering Guide

## What This Project Is

COUBES (Container Orchestration Universal Benchmark for Evaluating Schedulers) is a proof-of-concept research framework that bridges **CloudSim 7G** (a discrete-event cloud simulation framework) with a **live Kubernetes scheduler**. The goal is to benchmark real K8s schedulers using CloudSim's simulation infrastructure, collecting metrics like energy consumption, bin-packing efficiency, and time-to-completion.

The adapter implements a fake Kubernetes API server that real kube-scheduler instances connect to, eliminating the need for KWOK or a full Kubernetes cluster. It also supports a standalone test mode with built-in round-robin scheduling for rapid development.

This is an MSci research project (2024–2025). The codebase is intentionally experimental — prefer clarity and correctness over premature abstraction.

---

## Repository Layout

```
cloudsim-experimental/
├── src/main/java/org/example/
│   ├── kubernetes_broker/     # Custom CloudSim broker + datacenter + VM classes
│   ├── metrics/               # SimulationMetrics, TimeWeightedMetric, PerformanceMetrics
│   ├── helper/                # Constants, Helper (host/VM factory, result printing)
│   ├── examples/              # Runnable simulation examples
│   └── testSuite/             # Benchmark test scenarios
├── k8s-cloudsim-adapter/      # Go HTTP adapter (the middleware)
│   ├── communicator/          # Core bridge logic, struct definitions, conversions
│   ├── fakeapi/               # Fake Kubernetes API server handlers
│   ├── store/                 # In-memory node/pod store with watch broadcast
│   ├── scheduler/             # SchedulingRound (full mode) + TestModeScheduler
│   └── utils/
├── second-scheduler/          # Docker config for running kube-scheduler
├── run_test.sh                # Test runner script with infra management + hang detection
├── run_all_tests.sh           # Runs all test suite tests, reports summary
├── docs/                      # Architecture docs (DESIGN.md, scheduling-workflow.md)
└── k8s-in-the-loop/           # Separate sub-project (do not modify unless instructed)
```

The `cloudsim-7.0/` sibling folder contains the **vanilla CloudSim 7.0.1 source** for reference. Do not modify it.

---

## Technology Stack

| Layer | Technology |
|---|---|
| Simulation | CloudSim 7G (Java 21, Maven) |
| Adapter/Middleware | Go (gorilla/mux, fake Kubernetes API server) |
| Scheduler | Standard `kube-scheduler` (connects directly to adapter) |
| Build | `mvn clean install` (Java), `go build -o adapter-linux .` (Go) |

---

## Core Concept: The Scheduling Loop

CloudSim VMs → adapter `/nodes` → in-memory nodes exposed via fake API  
CloudSim Cloudlets → adapter `/schedule` → in-memory pods exposed via fake API → K8s scheduler assigns pods to nodes via binding API → adapter returns node assignments → CloudSim binds cloudlets to VMs

**Key invariant:** CloudSim IDs and Kubernetes names are kept in sync via naming conventions:
- CloudSim VM `id=N` ↔ K8s node `csnode-N`
- CloudSim Cloudlet `id=N` ↔ K8s pod `cspod-N`

---

## Key Classes

### `Live_Kubernetes_Broker_Ex` (primary broker)
Extends `DatacenterBrokerEX`. Main entry point for all simulations using the live K8s scheduler.

Features:
- Sends `X-Round-Id` header on all HTTP calls for cross-layer log correlation
- Increments `roundCounter` before each `/schedule` call
- Logs include `[round=N]` prefix for scheduling events
- Supports optional `PerformanceMetrics` for per-pod scheduling latency tracking
- Throughput metrics: EWMA, sliding window, overall average

### `PowerDatacenterCustom`
Extends `PowerDatacenter`. Additions:
- **Log levels**: `LogLevel.QUIET` / `NORMAL` / `VERBOSE` — controls per-tick output verbosity. Set via `setLogLevel()`. QUIET suppresses consolidation, energy, and utilization output.
- Tracks **consolidation ratio** (cloudlets/active VMs) as a time-weighted metric
- Supports **deferred VM destruction** via `CloudActionTagsEx.VM_DELAYED_DESTROY`
- `disableDeallocation` flag: when `true`, VMs are never auto-destroyed

### `PerformanceMetrics`
Tracks per-pod scheduling latency (submission → binding timestamp). Thread-safe.
- `recordSubmission(cloudletId)` — called in broker before sending to adapter
- `recordBatchDecision(response)` — called when adapter returns binding timestamps
- `getAverageLatencyMs()`, `getP99LatencyMs()`, `getThroughputPodsPerSec()`
- Integrated into `SimulationMetrics.printSummary()` when provided

### `SimulationMetrics`
Wraps a `PowerDatacenterCustom` and a VM list. Prints: simulated time, wall-clock time, energy (Wh), host count, consolidation average, VM count, throughput, and optionally scheduling latency metrics.

---

## Adapter API (Go, port 8080)

| Endpoint | Method | Purpose |
|---|---|---|
| `POST /nodes` | POST | Sync CloudSim VMs → adapter nodes (diff-based) |
| `POST /schedule` | POST | Submit SimulationSnapshot (nodes + pods + completedIds); returns BatchDecision |
| `POST /schedule-pods` | POST | Legacy: submit batch of pods only; returns BatchDecision |
| `POST /pods/update-state` | POST | Delete a finished pod; return rescheduling decisions |
| `DELETE /reset` | DELETE | Delete all pods and nodes, reset scheduling round |
| `GET /pods/{id}/status` | GET | Get in-memory pod status by CloudSim ID |

### Adapter Logging

All handlers emit **structured JSON log lines** with fields: `ts`, `action`, `roundId`, `podCount`, `nodeCount`, `durationMs`, `result`, `scheduled`, `unschedulable`. The `roundId` is extracted from the `X-Round-Id` HTTP header sent by the Java broker.

The `scheduler/scheduler.go` logs round lifecycle: start (expected decisions), each binding, each failure, round completion (with timing), and timeout diagnostics (scheduled/failed/pending counts).

### Adapter Modes

1. **Test Mode** (`--test-mode`): Built-in round-robin scheduler. No Docker/kube-scheduler needed.
2. **Full Mode** (default): Fake Kubernetes API server. Real kube-scheduler connects on port 8080.

---

## Metrics Collected

| Metric | Where | How |
|---|---|---|
| Energy consumption (Wh) | `PowerDatacenterCustom.getPower()` | Linear interpolation between scheduling intervals |
| Time-weighted consolidation | `PowerDatacenterCustom.getConsolidationAverage()` | `TimeWeightedMetric` updated in `updateCloudletProcessing()` |
| Wall-clock time | `SimulationMetrics` | `Instant.now()` before/after simulation |
| Simulated time | `CloudSim.startSimulation()` return value | CloudSim internal clock |
| Scheduling throughput (pods/sec) | `Live_Kubernetes_Broker_Ex` | EWMA + sliding window + overall average |
| Scheduling latency (ms) | `PerformanceMetrics` | Per-pod submission→binding timestamp delta |
| SLA violations | `Helper.printResults()` | From VM/host state history |

---

## Test Scenarios (testSuite/)

| Test | What it measures |
|---|---|
| `Fragmentation_Test` | Bin-packing under mixed workloads; 2 waves, `disableDeallocation=true` |
| `Fragmentation_Test_Large` | Rescheduling stress test; 50 cloudlets, 2 VMs, multiple rounds |
| `Fragmentation_Test_5Wave` | 5 waves of mixed cloudlets, multi-round rescheduling |
| `Performance_vs_Efficiency_Test` | Two hosts with different power models; energy vs throughput tradeoff |
| `Undercrowding_Test` | Sparse workload; idle energy waste |
| `Scheduler_Scalability_Test` | Scheduling latency at 4:1 and 20:1 pod-to-node ratios |
| `Scheduler_Latency_Test` | Per-pod scheduling latency under increasing load |
| `MultiPE_Pod_Test` | Multi-PE pods (3 PEs each) on 5-PE nodes; capacity tracking |
| `Oversized_Pod_Test` | Pod too large for any node; graceful termination |
| `Heterogeneous_Node_Test` | Nodes with different PE counts (2, 4, 8); correct placement |
| `Single_Pod_Test` | Minimal sanity check: 1 host, 1 VM, 1 pod |
| `Empty_Wave_Test` | Wave 2 arrives after wave 1 completes; no rescheduling needed |
| `Rapid_Completion_Test` | 10 pods complete simultaneously; batched rescheduling |
| `Queue_Priority_Test` | Volcano multi-queue: 2 queues with different weights competing for resources |
| `Gang_Scheduling_Test` | Gang scheduling: 3 gangs scheduled atomically (all-or-nothing) |
| `Gang_Constrained_Test` | Gang too large for cluster: Volcano rejects immediately, kube-scheduler deadlocks |
| `Gang_PartialFit_Test` | Gang that almost fits: needs fillers to complete first |
| `Gang_Mixed_Test` | Gang + independent pods competing for resources |
| `Gang_Energy_Test` | Energy cost comparison of gang waiting patterns |
| `Queue_Starvation_Test` | Sustained overload: proportion plugin vs greedy scheduling |
| `Overload_Comparison_Test` | 71 pods, 5 heterogeneous VMs, mixed gangs + queues — full comparison |

---

## Running the Project

### Preferred: run_test.sh

`run_test.sh` is the single entry point for running tests. It handles everything: killing stale processes, compiling both Java and Go, running Go unit tests, starting/stopping the adapter and scheduler, hang detection with auto-recovery, and output filtering. **Always use this script** — do not run the adapter or simulation manually unless debugging.

If you find yourself repeatedly doing something before or after `run_test.sh`, that step should be added to the script itself.

```bash
# Show all options
bash run_test.sh --help

# Test mode (no Docker required) — builds everything, runs test
bash run_test.sh --test-mode org.example.testSuite.Fragmentation_Test_Large

# Full mode (real kube-scheduler via Docker)
bash run_test.sh org.example.testSuite.Scheduler_Scalability_Test

# Skip compilation (use existing binaries)
bash run_test.sh --test-mode --no-compile org.example.testSuite.Fragmentation_Test_Large

# Show full unfiltered output
bash run_test.sh --test-mode --no-filter org.example.testSuite.Fragmentation_Test
```

**Options:**
- `--test-mode` — built-in round-robin scheduler, no Docker needed
- `--no-compile` — skip Go build, Go tests, and Java compilation
- `--no-filter` — show full simulation output instead of filtered summary
- `--help` — show usage

**What the script does automatically:**
1. Kills stale adapter and Java processes
2. Builds Go adapter + runs Go scheduler tests (unless `--no-compile`)
3. Compiles Java (unless `--no-compile`)
4. Starts adapter, starts scheduler (full mode only), resets state
5. Runs the simulation with hang detection (45s timeout)
6. Auto-recovers once from a hang (restarts scheduler, retries)
7. Filters output to show only results, metrics, and errors (unless `--no-filter`)

See `operational-runbook.md` for manual startup and debugging procedures.

### Comparing schedulers

```bash
# Run same test on kube-scheduler then Volcano, produce comparison CSV
bash compare_schedulers.sh Fragmentation_Test
bash compare_schedulers.sh --no-compile Overload_Comparison_Test
```

Outputs timestamped CSVs in `results/` with raw metric values and relative scores
(>1.0 = Volcano better). See `compare-schedulers-script.md` for full details.

---

## Important Conventions

- **Never modify `cloudsim-7.0/`** — it is the upstream reference.
- **`k8s-in-the-loop/`** is a separate sub-project; do not touch it unless explicitly asked.
- Always call `broker.sendResetRequestToControlPlane()` at the end of each simulation run.
- Use `pkill -x adapter-linux` (exact match), never `pkill -f adapter-linux` (matches shell processes).
- Use `docker compose down && up` instead of `restart` to avoid bind mount inode issues.
- The `UtilizationModelSlice` class has a known bug: `Math.min(0, 1/PEs)` always returns 0.
