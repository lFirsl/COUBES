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

### Node Sync (`/nodes`)
The adapter performs a **diff** between the current KWOK cluster state and the incoming CloudSim VM list:
- Nodes in cluster but not in CloudSim → deleted
- Nodes in CloudSim but not in cluster → created
- After changes, polls `AreAllNodesReady()` with up to 20 retries (1s each)

### Pod Scheduling (`/schedule-pods`)
1. Creates all pods in KWOK (with CPU resource requests from `CsPod.Pes`)
2. Polls `AreAllPodsScheduled()` with up to 30 retries (1s each)
3. A pod is considered "done" if it has a `nodeName` (scheduled) OR is marked `Unschedulable`
4. Returns the full pod list with node assignments

### Pod Deletion + Rescheduling (`/pods/update-state`)
1. Captures pre-deletion pod statuses
2. Deletes the specified pod
3. If all pods were already scheduled before deletion → returns empty (no rescheduling needed)
4. Polls for status changes with up to 30 retries (250ms each)
5. Returns pods whose status changed (i.e., newly scheduled)

---

## KWOK Cluster Setup

KWOK (Kubernetes Without Kubelet) runs a full K8s control plane (etcd, kube-apiserver, kube-controller-manager, kube-scheduler) but replaces kubelets with a lightweight controller that simulates node/pod lifecycle.

Fake nodes require:
- Label `type=kwok`
- Annotation `kwok.x-k8s.io/node=fake`
- Taint `kwok.x-k8s.io/node=fake:NoSchedule`

Fake pods require:
- Toleration for `kwok.x-k8s.io/node=fake:NoSchedule`
- NodeAffinity requiring `type=kwok`
- `schedulerName` matching the target scheduler

The KWOK controller handles pod lifecycle stages (pod-ready, pod-complete, pod-delete) via `Stage` CRDs defined in `kwok.yaml`.

---

## Second Scheduler (Bin-Packing)

The `second-scheduler/` folder provides a second instance of `kube-scheduler` configured with `MostAllocated` scoring (bin-packing) instead of the default `LeastAllocated` (spreading). This is used to compare scheduling strategies.

To target it, pass `--scheduler=my-scheduler` to the Go adapter:
```bash
go run main.go --scheduler=my-scheduler
```

The scheduler runs on port 10260 and connects to the same KWOK cluster via the kubeconfig in `second-scheduler/`.

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
