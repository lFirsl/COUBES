---
inclusion: manual
---

# COUBES — Deep Architecture & Design Knowledge

## Concept Map: CloudSim ↔ Kubernetes Terminology

| CloudSim | Kubernetes (via KWOK) | Notes |
|---|---|---|
| `Host` | Physical machine (not represented in K8s) | Hosts exist only in CloudSim |
| `Vm` / `GuestEntity` | Node (`csnode-{id}`) | VMs become fake KWOK nodes |
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
                                          └─ POST /nodes  →  adapter syncs KWOK nodes
                                     └─ submitCloudlets()
                                          └─ POST /schedule-pods  →  K8s schedules pods
                                          └─ processScheduledPodsResponse()
                                               └─ cloudSimAllocation()
                                                    └─ sendNow(CLOUDLET_SUBMIT)
```

## Detailed Flow: Cloudlet Completion

```
processCloudletReturn(cloudlet)
  └─ updateMiddleware(cloudlet)
       └─ POST /pods/update-state  [delete finished pod, watch for rescheduling]
            └─ adapter: DeletePodAndWaitForRescheduling()
                 └─ if pending pods exist → K8s reschedules them
                 └─ returns newly scheduled pods
       └─ processScheduledPodsResponse(newPods)
            └─ cloudSimAllocation()  [submit newly freed cloudlets to CloudSim]
  └─ super.processCloudletReturn() [only if no more pending cloudlets]
```

This rescheduling loop is what enables dynamic workloads: when a cloudlet finishes and its pod is deleted, the K8s scheduler can place previously-pending pods onto the now-freed node.

---

## Adapter Internals

### Test Mode vs Full Mode

The adapter operates in one of two modes:

**Test Mode** (`--test-mode`):
- Built-in round-robin scheduler (`TestModeScheduler`)
- No external dependencies (no KWOK, no kube-scheduler)
- Fake Kubernetes API routes are not registered
- Scheduling is synchronous and deterministic
- Pod `i` assigned to `sortedNodes[i % M]` (lexicographic node order)
- Ideal for rapid testing and development

**Full Mode** (default):
- Implements a fake Kubernetes API server
- Real kube-scheduler connects to adapter on port 8080
- Supports protobuf bindings for pod-to-node assignments
- Scheduling is asynchronous (waits for kube-scheduler decisions)
- Supports multiple scheduler profiles (LeastAllocated, MostAllocated)

### Node Sync (`/nodes`)
The adapter performs a **diff** between the current in-memory store and the incoming CloudSim VM list:
- Nodes in store but not in CloudSim → deleted
- Nodes in CloudSim but not in store → created
- In full mode, nodes are exposed via the fake API server for kube-scheduler to query

### Pod Scheduling (`/schedule-pods`)

**Test Mode:**
1. Creates pods in the in-memory store
2. Calls `TestModeScheduler.Schedule(pods, nodes)` synchronously
3. Returns assignments immediately (no polling)

**Full Mode:**
1. Creates all pods in the in-memory store (exposed via fake API)
2. Kube-scheduler watches for unscheduled pods via the fake API
3. Scheduler sends binding requests (protobuf format) to assign pods to nodes
4. Adapter polls `AreAllPodsScheduled()` with up to 30 retries (1s each)
5. A pod is considered "done" if it has a `nodeName` (scheduled) OR is marked `Unschedulable`
6. Returns the full pod list with node assignments

### Pod Deletion + Rescheduling (`/pods/update-state`)

**Test Mode:**
1. Deletes the specified pod from the store
2. Collects all pending pods (pods with no `nodeName`)
3. If pending pods exist, calls `TestModeScheduler.Schedule()` and returns new assignments
4. Returns empty `BatchDecision` if no pending pods

**Full Mode:**
1. Captures pre-deletion pod statuses
2. Deletes the specified pod
3. If all pods were already scheduled before deletion → returns empty (no rescheduling needed)
4. Polls for status changes with up to 30 retries (250ms each)
5. Returns pods whose status changed (i.e., newly scheduled by kube-scheduler)

---

## KWOK Cluster Setup (Legacy - No Longer Required)

**Note:** KWOK is no longer required for COUBES. The adapter now implements a fake Kubernetes API server directly, eliminating the need for KWOK or a full Kubernetes cluster.

For historical reference, KWOK (Kubernetes Without Kubelet) ran a full K8s control plane (etcd, kube-apiserver, kube-controller-manager, kube-scheduler) but replaced kubelets with a lightweight controller that simulated node/pod lifecycle.

The current implementation provides the same functionality without the overhead of running a full cluster.

---

## Second Scheduler (Multi-Profile Configuration)

The `second-scheduler/` folder provides a custom `kube-scheduler` instance with two scheduling profiles:

1. **`default-scheduler`** — LeastAllocated (spreading): distributes pods evenly across nodes
2. **`my-scheduler`** — MostAllocated (bin-packing): packs pods tightly onto fewer nodes

Both profiles use equal CPU/memory weights and disable all score plugins except `NodeResourcesFit`.

To target a specific profile, pass `--scheduler=<profile-name>` to the Go adapter:
```bash
# Spreading strategy
go run main.go --scheduler=default-scheduler

# Bin-packing strategy
go run main.go --scheduler=my-scheduler
```

The scheduler runs in a Docker container with `network_mode: host` to connect to the adapter on `localhost:8080`. It listens on port 10260 for metrics/health checks.

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

Host power models used:
- `PowerModelLinear(maxWatts, staticPowerPercent)` — linear between idle and max
- `PowerModelSpecPowerHpProLiantMl110G4Xeon3040` / `G5Xeon3075` — real HP server specs

### Consolidation Ratio
Defined as `activeCloudlets / activeVMs` at each scheduling interval. "Active" means the VM's cloudlet scheduler has at least one cloudlet in exec or waiting lists. Tracked as a time-weighted average via `TimeWeightedMetric`.

A higher consolidation ratio means cloudlets are packed onto fewer VMs — better bin-packing.

### Throughput
Measured in the broker as pods scheduled per second. Three variants:
- `tpOverall()` — total pods / total wall time across all batches
- `tpWindowAvg()` — sliding window over last 10 batches
- `tpEwma()` — exponentially weighted moving average (α=0.3)

### SLA Violations
Computed in `Helper.getSlaMetrics()` from VM state history. Measures time periods where allocated MIPS < requested MIPS. Also tracks degradation due to migration.

---

## Known Issues / Limitations

1. **`UtilizationModelSlice` bug**: `Math.min(0, 1/PEs)` always returns 0 — the model never reports any utilization. This is a latent bug.

2. **Synchronous HTTP calls**: The broker blocks the CloudSim simulation thread while waiting for the adapter. This means wall-clock time includes K8s scheduling latency, which is intentional for benchmarking but means simulated time ≠ wall time.

3. **`Live_Kubernetes_Broker` (old)**: Has a double-submit bug in `submitCloudlets()` — it calls `submitCloudletToVmInCloudSim()` twice for "Scheduled" pods. Use `Live_Kubernetes_Broker_Ex` instead.

4. **No container support in adapter**: The adapter's `CsNode.Type` field distinguishes "vm" vs "container" but the K8s node creation doesn't differentiate — all become KWOK nodes regardless.

5. **Single namespace**: All pods and nodes are created in the `default` namespace. Multi-tenant scenarios are not supported.

6. **`disableDeallocation` flag**: When `true` in `PowerDatacenterCustom`, VMs are never destroyed. This is needed for fragmentation tests where VMs must persist across cloudlet completions, but it means energy is charged for idle VMs indefinitely.

---

## Extension Points

To add a new scheduler for benchmarking:
1. Deploy it against the KWOK cluster (see `second-scheduler/` as a template)
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
