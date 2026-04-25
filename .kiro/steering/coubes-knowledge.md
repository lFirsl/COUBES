---
inclusion: manual
---

# COUBES — Deep Architecture & Design Knowledge

## Concept Map: CloudSim ↔ Kubernetes Terminology

| CloudSim | Kubernetes | Notes |
|---|---|---|
| `Host` | Physical machine (not represented in K8s) | Hosts exist only in CloudSim |
| `Vm` / `GuestEntity` | Node (`csnode-{id}`) | VMs become fake nodes |
| `Cloudlet` | Pod (`cspod-{id}`) | Cloudlets become fake pods |
| `DatacenterBroker` | — | Orchestrates the whole flow |
| `Datacenter` | — | Manages hosts and VMs in CloudSim |
| Cloudlet scheduled on VM | Pod assigned to node | The K8s scheduler makes this decision |

The fundamental insight: CloudSim's VM-to-host allocation is done by CloudSim internally, but **cloudlet-to-VM allocation is delegated to the K8s scheduler** by treating VMs as nodes and cloudlets as pods.

---

## Detailed Flow: Simulation Startup

```
CloudSim.init()
  └─ CloudSim.startSimulation()
       └─ Broker.startEntity()
            └─ RESOURCE_CHARACTERISTICS_REQUEST
                 └─ processResourceCharacteristics()
                      └─ createVmsInDatacenter()
                           └─ [VMs created in datacenter]
                                └─ processVmCreateAck() [for each VM]
                                     └─ sendAllActiveNodesToControlPlane()
                                          └─ POST /nodes  →  adapter syncs nodes
                                     └─ submitCloudlets()
                                          └─ POST /schedule  →  K8s schedules pods
                                          └─ processBatchDecision()
                                               └─ cloudSimAllocation()
                                                    └─ sendNow(CLOUDLET_SUBMIT)
```

## Detailed Flow: Cloudlet Completion

```
processCloudletReturn(cloudlet)
  └─ completedSinceLastRound.add(cloudletId)
  └─ if cloudletsSubmittedToMiddle not empty:
       └─ schedule RESCHEDULE_PENDING event at +1s
  └─ else if all done:
       └─ finishExecution()
```

On RESCHEDULE_PENDING:
```
processOtherEvent(RESCHEDULE_PENDING)
  └─ submitCloudlets()
       └─ POST /schedule (with completedPodIds + pending pods)
       └─ processBatchDecision()
```

---

## Adapter Internals

### Test Mode vs Full Mode

**Test Mode** (`--test-mode`):
- Built-in round-robin scheduler (`TestModeScheduler`)
- No external dependencies (no Docker, no kube-scheduler)
- Fake Kubernetes API routes are not registered
- Scheduling is synchronous and deterministic
- Pod `i` assigned to `sortedNodes[i % M]` (lexicographic node order)

**Full Mode** (default):
- Implements a fake Kubernetes API server
- Real kube-scheduler connects to adapter on port 8080
- Supports protobuf bindings for pod-to-node assignments
- Scheduling is asynchronous (waits for kube-scheduler decisions)
- Supports multiple scheduler profiles (LeastAllocated, MostAllocated)

### Structured Logging

All adapter handlers emit **structured JSON log lines** via `logJSON()`:
```json
{"ts":"...","action":"HandleSchedule","roundId":"3","podCount":15,"durationMs":2,"result":"ok","scheduled":15,"unschedulable":0}
```

The `roundId` is extracted from the `X-Round-Id` HTTP header sent by the Java broker. This enables cross-layer log correlation: grep for a round ID to see the full lifecycle across Java, adapter, and scheduler logs.

The `scheduler/scheduler.go` logs round lifecycle events:
- `Scheduling round started: expecting N decisions`
- `Binding: pod=cspod-X -> node=csnode-Y (M/N resolved)`
- `Round complete: X scheduled, Y failed in Zms`
- `TIMEOUT: X scheduled, Y failed, Z still pending after 60s`

### Node Sync (`/nodes`)
Diff-based: adds missing nodes, removes stale nodes to match the incoming list exactly.

### Pod Scheduling (`/schedule`)
The primary scheduling endpoint. Accepts a `SimulationSnapshot` containing nodes, pods, and completed pod IDs. In test mode, calls `TestModeScheduler.Schedule()` synchronously. In full mode, begins a `SchedulingRound` and blocks until all pods are resolved or timeout.

### Pod Scheduling (`/schedule-pods`)
Legacy endpoint. Accepts `[]CsPod` only (no node sync or completed IDs). Still functional but `/schedule` is preferred.

---

## Second Scheduler (Multi-Profile Configuration)

The `second-scheduler/` folder provides a custom `kube-scheduler` instance with two scheduling profiles:

1. **`default-scheduler`** — LeastAllocated (spreading): distributes pods evenly across nodes
2. **`my-scheduler`** — MostAllocated (bin-packing): packs pods tightly onto fewer nodes

Both profiles use equal CPU/memory weights and disable all score plugins except `NodeResourcesFit`.

The scheduler runs in a Docker container and connects to the adapter's fake API server. It listens on port 10260 for metrics/health checks.

### Configuration Details

The scheduler uses a `KubeSchedulerConfiguration` with:
- `leaderElection.leaderElect: false` (no leader election needed for single-instance)
- Two profiles with different `NodeResourcesFit` scoring strategies
- All other score plugins disabled for predictable, resource-focused scheduling
- Connects to the adapter's fake API server (no real Kubernetes cluster required)

---

## Metrics Design

### Energy
`PowerDatacenterCustom` inherits energy tracking from `PowerDatacenter`. Energy is accumulated as `W*sec` using linear interpolation between CPU utilization samples at each scheduling interval. Convert to Wh: `getPower() / 3600`.

### Consolidation Ratio
Defined as `activeCloudlets / activeVMs` at each scheduling interval. Tracked as a time-weighted average via `TimeWeightedMetric`. Higher = better bin-packing.

### Throughput
Measured in the broker as pods scheduled per second. Three variants:
- `tpOverall()` — total pods / total wall time across all batches
- `tpWindowAvg()` — sliding window over last 10 batches
- `tpEwma()` — exponentially weighted moving average (α=0.3)

### Scheduling Latency
Tracked by `PerformanceMetrics`. Per-pod latency = time between `recordSubmission()` (before HTTP call) and `bindingTimestamp` (set by adapter when binding is recorded). Reports average, P99, and throughput (pods/sec).

### SLA Violations
Computed in `Helper.getSlaMetrics()` from VM state history. Measures time periods where allocated MIPS < requested MIPS.

---

## Known Issues / Limitations

1. **Rescheduling loop bug (FIXED)**: When more cloudlets than VMs are submitted, four
   interacting bugs prevented the rescheduling loop from working: the test mode scheduler
   ignored capacity, the adapter didn't track running pods between rounds, the broker
   didn't re-send pending pods, and CloudSim's event chain died when cloudlets arrived on
   idle VMs with stale 0-MIPS allocations. All four are fixed. Full details, affected files,
   and a "if this breaks again" troubleshooting guide are in `rescheduling-loop-fix.md`.

2. **`UtilizationModelSlice` bug**: `Math.min(0, 1/PEs)` always returns 0.

3. **Synchronous HTTP calls**: The broker blocks the CloudSim simulation thread while waiting for the adapter. Wall-clock time includes K8s scheduling latency.

4. **`Live_Kubernetes_Broker` (old)**: Has a double-submit bug. Always use `Live_Kubernetes_Broker_Ex`.

5. **Single namespace**: All pods and nodes are created in the `default` namespace.

6. **`disableDeallocation` flag**: When `true`, VMs are never destroyed. Energy is charged for idle VMs indefinitely.

7. **Per-host MIPS output**: CloudSim's base `PowerHost.updateCloudletsProcessing()` prints per-host MIPS allocation every tick. This cannot be suppressed via `LogLevel.QUIET` since it's in upstream code.

---

## Extension Points

To add a new scheduler for benchmarking:
1. Deploy it against the adapter (see `second-scheduler/` as a template)
2. Pass its name via `--scheduler=<name>` to the adapter
3. Run the same test suite and compare `SimulationMetrics` output

To add a new metric:
1. Add a `TimeWeightedMetric` field to `PowerDatacenterCustom` (or a new class)
2. Update it in `updateCloudletProcessing()`
3. Expose it via a getter and print it in `SimulationMetrics.printSummary()`

To add a new test scenario:
1. Create a class in `testSuite/` following the pattern of existing tests
2. Define hosts, VMs (`PowerVmCustom` with `preferredHostId` if needed), and cloudlets
3. Use `Live_Kubernetes_Broker_Ex` and `PowerDatacenterCustom`
4. Call `broker.sendResetRequestToControlPlane()` at the end
