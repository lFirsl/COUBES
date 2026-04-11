---
inclusion: always
---

# COUBES Project — Steering Guide

## What This Project Is

COUBES (Container Orchestration Universal Benchmark for Evaluating Schedulers) is a proof-of-concept research framework that bridges **CloudSim 7G** (a discrete-event cloud simulation framework) with a **live Kubernetes scheduler** running on a KWOK-emulated cluster. The goal is to benchmark real K8s schedulers using CloudSim's simulation infrastructure, collecting metrics like energy consumption, bin-packing efficiency, and time-to-completion.

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
| Adapter/Middleware | Go (gorilla/mux, client-go) |
| K8s Cluster Emulation | KWOK (Kubernetes Without Kubelet) |
| Scheduler | Standard `kube-scheduler` (default or custom via Docker) |
| Build | `mvn clean install` (Java), `go run main.go` (Go) |

---

## Core Concept: The Scheduling Loop

CloudSim VMs → adapter `/nodes` → KWOK fake nodes  
CloudSim Cloudlets → adapter `/schedule-pods` → KWOK fake pods → K8s scheduler assigns pods to nodes → adapter returns node assignments → CloudSim binds cloudlets to VMs

**Key invariant:** CloudSim IDs and Kubernetes names are kept in sync via naming conventions:
- CloudSim VM `id=N` ↔ K8s node `csnode-N`
- CloudSim Cloudlet `id=N` ↔ K8s pod `cspod-N`
- The `cloudsim.io/id` annotation on K8s objects is the canonical ID mapping.

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
| `POST /nodes` | POST | Sync CloudSim VMs → KWOK nodes (diff-based: adds missing, removes stale) |
| `POST /schedule-pods` | POST | Submit batch of cloudlets as pods; blocks until all scheduled; returns assignments |
| `POST /pods/update-state` | POST | Delete a finished pod; watch for rescheduling of pending pods; return newly scheduled pods |
| `DELETE /reset` | DELETE | Delete all pods and nodes from the cluster |
| `GET /pods/{id}/status` | GET | Get in-memory pod status by CloudSim ID |

The adapter runs on `http://localhost:8080`. The Java broker hardcodes this URL as `CONTROL_PLANE_URL`.

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

Prerequisites: Java 21, Maven, Go, Docker Desktop, KWOK, CloudSim 7.0.1 installed locally.

```bash
# 1. Build CloudSim (once, from cloudsim-7.0/)
mvn clean install -DskipTests

# 2. Start KWOK cluster
kwokctl create cluster
kubectl cluster-info --context kwok-kwok

# 3. (Optional) Start a second scheduler for bin-packing
#    See second-scheduler/README.md
docker-compose -f second-scheduler/docker-compose.yml up -d

# 4. Start the Go adapter
cd k8s-cloudsim-adapter
go run main.go
# Default: listens on :8080, connects to ~/.kube/config, uses "default-scheduler"
# Use --scheduler=my-scheduler to target a different scheduler

# 5. Run a test
mvn exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test"
```

---

## Important Conventions

- **Never modify `cloudsim-7.0/`** — it is the upstream reference.
- **`k8s-in-the-loop/`** is a separate sub-project; do not touch it unless explicitly asked.
- The adapter is stateless between simulations — always call `broker.sendResetRequestToControlPlane()` at the end of each simulation run.
- KWOK nodes require the `type=kwok` label and `kwok.x-k8s.io/node=fake` taint; pods require the matching toleration. These are set automatically by the adapter's conversion utilities.
- Pod CPU resources are set from `CsPod.Pes` (integer cores); node CPU/RAM from `CsNode.Pes` and `CsNode.RAMAval` (MB).
- The `UtilizationModelSlice` class has a known bug: `Math.min(0, 1/PEs)` always returns 0. This is not yet fixed.
