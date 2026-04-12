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
│   ├── metrics/               # SimulationMetrics, TimeWeightedMetric
│   ├── helper/                # Constants, Helper (host/VM factory, result printing)
│   ├── examples/              # Runnable simulation examples
│   └── testSuite/             # Benchmark test scenarios
├── k8s-cloudsim-adapter/      # Go HTTP adapter (the middleware)
│   ├── communicator/          # Core bridge logic, struct definitions, conversions
│   ├── kube_client/           # Kubernetes API client wrappers
│   └── utils/
├── second-scheduler/          # Docker/KWOK config for running a second K8s scheduler
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
| Build | `mvn clean install` (Java), `go run main.go` (Go) |

---

## Core Concept: The Scheduling Loop

CloudSim VMs → adapter `/nodes` → in-memory nodes exposed via fake API  
CloudSim Cloudlets → adapter `/schedule-pods` → in-memory pods exposed via fake API → K8s scheduler assigns pods to nodes via binding API → adapter returns node assignments → CloudSim binds cloudlets to VMs

**Key invariant:** CloudSim IDs and Kubernetes names are kept in sync via naming conventions:
- CloudSim VM `id=N` ↔ K8s node `csnode-N`
- CloudSim Cloudlet `id=N` ↔ K8s pod `cspod-N`
- The `cloudsim.io/id` annotation on K8s objects is the canonical ID mapping.

**Architecture:** The adapter implements a fake Kubernetes API server that kube-scheduler connects to directly. No real Kubernetes cluster or KWOK is required. The adapter maintains an in-memory store of nodes and pods, exposing them through standard Kubernetes API endpoints.

---

## Key Classes

### `Live_Kubernetes_Broker_Ex` (primary broker)
Extends `DatacenterBrokerEX`. This is the main entry point for all simulations that use the live K8s scheduler.

Lifecycle:
1. `processVmCreateAck` → calls `sendAllActiveNodesToControlPlane()` → POST `/nodes`
2. `submitCloudlets()` → serializes cloudlets → POST `/schedule-pods` → receives pod-to-node assignments → calls `cloudSimAllocation()`
3. `processCloudletReturn()` → calls `updateMiddleware()` → POST `/pods/update-state` (deletes finished pod, watches for rescheduling of pending pods)

`Live_Kubernetes_Broker` is the **old/deprecated** version — kept only for backward compatibility with older examples.

### `PowerDatacenterCustom`
Extends `PowerDatacenter`. Additions:
- Tracks **consolidation ratio** (cloudlets/active VMs) as a time-weighted metric via `TimeWeightedMetric`.
- Supports **deferred VM destruction** via `CloudActionTagsEx.VM_DELAYED_DESTROY` — VMs are only destroyed once their cloudlet scheduler is empty.
- Supports `PowerVmCustom` with a `preferredHostId` for pinning VMs to specific hosts.
- `disableDeallocation` flag: when `true`, VMs are never auto-destroyed (used in fragmentation tests).

### `PowerVmCustom`
Extends `PowerVm`. Adds `preferredHostId` for host-pinning and overrides `getCurrentRequestedTotalMips()` / `getCurrentRequestedMips()` to compute demand from active cloudlets rather than static allocation.

### `SimulationMetrics`
Wraps a `PowerDatacenterCustom` and a VM list. Call `startWallClock()` before `CloudSim.startSimulation()` and `stopWallClock()` after. `printSummary(simTime)` prints: simulated time, wall-clock time, energy (Wh), host count, time-weighted consolidation average, VM count, and optionally throughput.

### `TimeWeightedMetric`
Accumulates a time-weighted average of any scalar metric (e.g. consolidation ratio). Call `add(time, value)` at each measurement point; call `average(untilTime)` at the end.

---

## Adapter API (Go, port 8080)

| Endpoint | Method | Purpose |
|---|---|---|
| `POST /nodes` | POST | Sync CloudSim VMs → adapter nodes (diff-based: adds missing, removes stale) |
| `POST /schedule-pods` | POST | Submit batch of cloudlets as pods; blocks until all scheduled; returns assignments |
| `POST /pods/update-state` | POST | Delete a finished pod; watch for rescheduling of pending pods; return newly scheduled pods |
| `DELETE /reset` | DELETE | Delete all pods and nodes from the adapter store |
| `GET /pods/{id}/status` | GET | Get in-memory pod status by CloudSim ID |

The adapter runs on `http://localhost:8080`. The Java broker hardcodes this URL as `CONTROL_PLANE_URL`.

### Adapter Modes

The adapter supports two operational modes:

1. **Test Mode** (`--test-mode`): Standalone operation with built-in round-robin scheduler. No external dependencies (no KWOK, no kube-scheduler). Ideal for rapid testing and development.

2. **Full Mode** (default): Implements a fake Kubernetes API server that a real kube-scheduler connects to. Supports protobuf bindings and all standard kube-scheduler features. Requires a running kube-scheduler instance (see `second-scheduler/`).

---

## Metrics Collected

| Metric | Where | How |
|---|---|---|
| Energy consumption (Wh) | `PowerDatacenterCustom.getPower()` | Linear interpolation between scheduling intervals |
| Time-weighted consolidation | `PowerDatacenterCustom.getConsolidationAverage()` | `TimeWeightedMetric` updated in `updateCloudletProcessing()` |
| Wall-clock time | `SimulationMetrics` | `Instant.now()` before/after simulation |
| Simulated time | `CloudSim.startSimulation()` return value | CloudSim internal clock |
| Scheduling throughput (pods/sec) | `Live_Kubernetes_Broker_Ex` | EWMA + sliding window + overall average |
| SLA violations | `Helper.printResults()` | From VM/host state history |

---

## Test Scenarios (testSuite/)

| Test | What it measures |
|---|---|
| `Fragmentation_Test` | Bin-packing under mixed workloads; `disableDeallocation=true` so VMs persist |
| `Performance_vs_Efficiency_Test` | Two hosts with different power models; measures energy vs throughput tradeoff |
| `Undercrowding_Test` | Sparse workload; measures idle energy waste |

---

## Running the Project

### Option 1: Test Mode (Standalone, No KWOK Required)

Prerequisites: Java 21, Maven, Go, CloudSim 7.0.1 installed locally.

```bash
# 1. Build CloudSim (once, from cloudsim-7.0/)
cd cloudsim-7.0
mvn clean install -DskipTests

# 2. Start the Go adapter in test mode
cd ../cloudsim-experimental/k8s-cloudsim-adapter
go run main.go --test-mode
# Adapter runs with built-in round-robin scheduler, no external dependencies

# 3. Run a test
cd ..
mvn exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test"
```

### Option 2: Full Mode (With Real kube-scheduler)

Prerequisites: Java 21, Maven, Go, Docker Desktop, CloudSim 7.0.1 installed locally.

```bash
# 1. Build CloudSim (once, from cloudsim-7.0/)
cd cloudsim-7.0
mvn clean install -DskipTests

# 2. Start the custom scheduler (supports both spreading and bin-packing)
cd ../cloudsim-experimental/second-scheduler
docker compose up -d
# Scheduler runs with two profiles: default-scheduler (LeastAllocated) and my-scheduler (MostAllocated)

# 3. Start the Go adapter
cd ../k8s-cloudsim-adapter
go run main.go --scheduler=default-scheduler
# Or use --scheduler=my-scheduler for bin-packing strategy

# 4. Run a test
cd ..
mvn exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test"
```

---

## Important Conventions

- **Never modify `cloudsim-7.0/`** — it is the upstream reference.
- **`k8s-in-the-loop/`** is a separate sub-project; do not touch it unless explicitly asked.
- The adapter is stateless between simulations — always call `broker.sendResetRequestToControlPlane()` at the end of each simulation run.
- In full mode, the adapter implements a fake Kubernetes API server. The kube-scheduler connects directly to the adapter on port 8080 (no KWOK required).
- In test mode, the adapter uses a built-in round-robin scheduler. Pods are assigned to nodes in lexicographic order: pod `i` → `sortedNodes[i % M]`.
- Pod CPU resources are set from `CsPod.Pes` (integer cores); node CPU/RAM from `CsNode.Pes` and `CsNode.RAMAval` (MB).
- The adapter supports protobuf bindings for real kube-scheduler integration. Binding requests are parsed to extract node assignments.
- The `UtilizationModelSlice` class has a known bug: `Math.min(0, 1/PEs)` always returns 0. This is not yet fixed.
