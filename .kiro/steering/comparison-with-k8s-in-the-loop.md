---
inclusion: manual
---

# COUBES vs Kubernetes-in-the-Loop: Architectural Comparison

## What Both Projects Share

Both projects solve the same fundamental problem: a discrete-event simulator needs to delegate scheduling decisions to a real Kubernetes scheduler, and the results must be fed back into the simulation. Both use a Go adapter as the bridge, and both use the real `kube-scheduler` binary without modification.

---

## The Core Architectural Difference: Emulation vs Fake API Server

This is the most important distinction between the two projects.

### COUBES (cloudsim-experimental): Emulation Approach

COUBES uses **KWOK** — a real, running Kubernetes cluster with a real etcd, kube-apiserver, kube-controller-manager, and kube-scheduler. The adapter (`k8s-cloudsim-adapter`) is a **client** of this cluster: it creates and deletes fake nodes and pods via the standard Kubernetes client-go API.

```
CloudSim (Java)
    │  HTTP (custom REST)
    ▼
k8s-cloudsim-adapter (Go)
    │  client-go (standard K8s client)
    ▼
KWOK cluster (real etcd + kube-apiserver + kube-scheduler)
    │  scheduling decision
    ▼
k8s-cloudsim-adapter reads back pod.Spec.NodeName
    │  HTTP response
    ▼
CloudSim binds cloudlet to VM
```

The adapter polls the cluster for results. The K8s scheduler is a component of the cluster, not a direct dependency of the adapter.

### k8s-in-the-loop: Fake API Server Approach

The misim-k8s-adapter implements a **fake kube-apiserver** entirely in memory. There is no etcd, no real cluster. The kube-scheduler binary connects directly to the adapter as if it were a real API server. The adapter speaks the full Kubernetes API protocol (including protobuf bindings, chunked watch streams, and Cluster API for autoscaling).

```
MiSim (Java)
    │  HTTP (custom REST: /updateNodes, /updatePods)
    ▼
misim-k8s-adapter (Go) ← acts as fake kube-apiserver
    │  real K8s API (watch, list, binding, status patch)
    ▼
kube-scheduler binary (unmodified)
    │  binding decision (protobuf POST /binding)
    ▼
misim-k8s-adapter captures binding
    │  HTTP response to /updatePods
    ▼
MiSim places pod on simulation node
```

The adapter is the API server. The scheduler is a client of the adapter.

---

## Detailed Comparison Table

| Dimension | COUBES (cloudsim-experimental) | k8s-in-the-loop |
|---|---|---|
| Simulation framework | CloudSim 7G (Java 21) | MiSim 3.3.1 (Java 8) |
| Simulation domain | Cloud resource scheduling (VMs, cloudlets, energy) | Microservice performance (latency, throughput, SLA) |
| K8s infrastructure | Real KWOK cluster (etcd + apiserver + scheduler) | Fake in-memory API server (no etcd, no cluster) |
| Adapter role | K8s client (creates/deletes resources) | K8s API server (serves resources to scheduler) |
| Scheduler connection | Scheduler is part of the cluster | Scheduler connects to adapter as its API server |
| Scheduling protocol | Indirect: adapter polls `pod.Spec.NodeName` | Direct: scheduler POSTs binding to adapter |
| Watch/streaming | Not used (polling) | Full K8s list-then-watch protocol |
| Protobuf | Not used | Used for binding requests (as real K8s does) |
| Cluster autoscaler | Not supported | Supported via Cluster API (MachineSets/Machines) |
| Node lifecycle | Diff-based sync on each simulation step | Event-driven (ADDED/MODIFIED/DELETED watch events) |
| Pod lifecycle | Batch submit → poll → batch result | Per-pod events; blocks until all pods resolved |
| Startup overhead | Requires Docker + KWOK cluster running | Just `./run.sh` — no external dependencies |
| Scheduler config | Via KWOK cluster config + `--scheduler` flag | Via standard K8s scheduler profile YAML |
| Multiple schedulers | Supported (second-scheduler/ folder) | Supported (multiple scheduler profiles) |
| Metrics focus | Energy, bin-packing, wall-clock time, throughput | Latency, SLA, microservice response time |
| CloudSim VM = K8s | Node (csnode-N) | Node |
| CloudSim Cloudlet = K8s | Pod (cspod-N) | Pod |
| ID mapping | Name convention + annotation | Name convention |
| Unschedulable pods | Detected via PodScheduled condition | Returned as BindingFailureInformation |
| Reset mechanism | DELETE /reset (deletes all pods + nodes) | New simulation run reinitialises all state |
| External dependencies | Docker Desktop, KWOK, kubectl | None (adapter is self-contained) |
| Java version | 21 | 8 (hard constraint from MiSim) |

---

## Simulation Model Differences

### CloudSim (COUBES)
- Models physical **hosts** with CPU, RAM, BW, storage
- Models **VMs** placed on hosts (CloudSim handles this allocation)
- Models **cloudlets** (tasks) placed on VMs (K8s handles this)
- Energy is a first-class metric: `PowerHost` + `PowerModel` + `PowerDatacenter`
- Simulation time is discrete but can be paused/resumed
- Cloudlets have length (MI), PEs, utilization models — they complete when MI is consumed

### MiSim (k8s-in-the-loop)
- Models **microservices** with request/response patterns
- Models **nodes** with CPU capacity
- Models **pods** as containers for microservice instances
- No energy model
- Simulation time is continuous (DESMO-J)
- Pods run indefinitely until explicitly terminated; load is driven by request arrival rates

---

## What k8s-in-the-loop Does Better

1. **No external cluster dependency** — the fake API server approach means zero infrastructure setup. Just start the adapter and the scheduler binary. COUBES requires Docker Desktop + KWOK running.

2. **Proper K8s protocol** — the scheduler talks to the adapter using the real K8s API (watch streams, protobuf bindings). This means any K8s component (not just the scheduler) can be plugged in without adapter changes.

3. **Event-driven, not polling** — the adapter receives binding decisions immediately via the `/binding` endpoint. COUBES polls `AreAllPodsScheduled()` in a loop with sleep delays, adding latency.

4. **Cluster autoscaler support** — the Cluster API integration allows the real cluster-autoscaler to run against the fake API server, enabling dynamic node provisioning scenarios.

5. **Cleaner separation** — the simulation pushes state to the adapter; the adapter serves it to K8s components. The data flow is unidirectional and well-defined.

6. **Scheduler config is pure YAML** — switching scheduling policies requires only changing a YAML file, not restarting a cluster or modifying adapter flags.

---

## What COUBES Does Better

1. **Energy modelling** — CloudSim's `PowerDatacenter` + `PowerModel` infrastructure gives COUBES detailed energy consumption metrics that MiSim has no equivalent for.

2. **Richer resource model** — CloudSim models hosts, VMs, and cloudlets with MIPS, RAM, BW, storage, and utilization models. MiSim only models CPU cores.

3. **Bin-packing metrics** — the consolidation ratio (cloudlets/active VMs) and time-weighted averages are purpose-built for scheduler benchmarking.

4. **Cloudlet completion semantics** — cloudlets have a defined length and finish when their MI is consumed. This maps naturally to batch workloads and makes time-to-completion meaningful.

5. **VM deallocation** — `PowerDatacenterCustom` can destroy idle VMs, freeing host resources. This enables dynamic resource reclamation scenarios.

6. **More mature test suite** — three distinct benchmark scenarios (fragmentation, performance vs efficiency, undercrowding) with clear metrics.

---

## The Key Insight for COUBES Evolution

The k8s-in-the-loop approach eliminates the biggest operational pain point of COUBES: the requirement for a running KWOK cluster. By implementing a fake API server in the adapter, the scheduler binary becomes a direct dependency of the adapter rather than a component of an external cluster. This would:

- Remove Docker Desktop and KWOK as prerequisites
- Eliminate polling delays (replaced by immediate binding callbacks)
- Enable cluster-autoscaler integration
- Make the system more portable and easier to test

The trade-off is implementation complexity: the fake API server must correctly implement the K8s API subset that the scheduler requires, including watch streams and protobuf decoding.
