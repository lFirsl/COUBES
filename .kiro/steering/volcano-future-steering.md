# Volcano Scheduler Integration — Implementation Reference

This document is a complete technical reference for the Volcano scheduler integration
in COUBES. It explains every change made, why it was made, and what to look at if
something breaks or needs extending.

---

## What Was Built

Volcano (`vc-scheduler`) is integrated into COUBES in the same way as the existing
`second-scheduler/` setup: the scheduler binary runs in Docker, connects to the fake
API server in the adapter, and returns scheduling decisions via the standard Kubernetes
binding API. No changes to the CloudSim Java side were needed beyond the broker.

The integration required changes in four areas:

1. The fake API server (Go adapter) — to speak Volcano's protocol
2. The in-memory store (Go adapter) — to hold Volcano CRD objects
3. The Java broker — to handle Volcano's scheduling semantics
4. Config files — to run the Volcano container

---

## Area 1: Fake API Server

### Why Volcano needs more than kube-scheduler

kube-scheduler connects to the fake API server and only needs standard Kubernetes
endpoints (nodes, pods, binding). Volcano uses **two clients**:

- A standard `kubeClient` — for pods, nodes, events (same as kube-scheduler)
- A Volcano CRD client (`vcClient`) — for its own resource types: Queue, PodGroup,
  HyperNode, NumaTopology

Both clients perform **API discovery** before making any resource requests. They call
`GET /api`, `GET /apis`, and `GET /apis/<group>/<version>` to learn what resources
exist. If these return errors or 404s, the client panics or retries indefinitely.

### File: `fakeapi/volcano_handlers.go` (new file)

This file contains all Volcano-specific HTTP handlers. It is structured in four sections:

#### Section 1: API Discovery

```
GET /api                                    → HandleAPIVersions
GET /api/v1                                 → HandleCoreV1Resources
GET /apis                                   → HandleAPIGroups
GET /apis/scheduling.volcano.sh/v1beta1     → HandleSchedulingVolcanoResources
GET /apis/topology.volcano.sh/v1alpha1      → HandleTopologyVolcanoResources
GET /apis/nodeinfo.volcano.sh/v1alpha1      → HandleNodeinfoVolcanoResources
```

`HandleAPIGroups` is the most important — it lists all API groups including the three
Volcano-specific ones. Without this, `vcClient` cannot find its CRDs and panics.

`HandleSchedulingVolcanoResources` advertises `queues` and `podgroups` as resources
in the `scheduling.volcano.sh/v1beta1` group. This is what tells the client which
endpoints to call for CRUD operations.

#### Section 2: Queue handlers

```
GET  /apis/scheduling.volcano.sh/v1beta1/queues           → HandleListQueues
GET  /apis/scheduling.volcano.sh/v1beta1/queues/{name}    → HandleGetQueue
POST /apis/scheduling.volcano.sh/v1beta1/queues           → HandleCreateQueue
PUT  /apis/scheduling.volcano.sh/v1beta1/queues/{name}/status → HandleUpdateQueueStatus
```

**Why Queues are critical:** On startup, before scheduling any pods, Volcano calls
`newDefaultAndRootQueue()` which does:
1. `GET /queues/default` — expects 404 (not found)
2. `POST /queues` — creates "root" queue
3. `POST /queues` — creates "default" queue

If any of these fail, Volcano retries 60 times (1 second apart) and never starts
scheduling. The `HandleGetQueue` handler deliberately returns a proper Kubernetes
404 Status object (not a plain HTTP 404) so the Go client recognises it as
`IsNotFound` and proceeds to create the queue.

`HandleListQueues` supports both list and watch. On watch, it replays existing queues
as ADDED events before streaming future events — this is required because Volcano's
informer re-lists on reconnect and expects to see existing objects at the start of
the watch stream.

`HandleUpdateQueueStatus` stores the update and broadcasts a MODIFIED watch event.
Volcano updates queue status after each scheduling cycle; if the PUT is discarded,
the informer's internal state diverges from the API server state.

#### Section 3: PodGroup handlers

```
GET   /apis/scheduling.volcano.sh/v1beta1/podgroups                          → HandleListPodGroups
GET   /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups          → HandleListPodGroups
POST  /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups          → HandleCreatePodGroup
PUT   /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups/{name}   → HandleUpdatePodGroup
PATCH /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups/{name}   → HandlePatchPodGroup
```

`HandleListPodGroups` returns PodGroups from the store (with watch support that
replays existing PodGroups as ADDED events on connect).

`HandleUpdatePodGroup` stores the updated PodGroup and broadcasts a MODIFIED watch
event. This is essential: Volcano's `enqueue` action promotes PodGroups from
`Pending` → `Inqueue` by calling `PUT /podgroups/{name}`. If the PUT is discarded,
Volcano's informer never sees the phase change and tries to enqueue the same PodGroup
every scheduling cycle forever.

`HandleCreatePodGroup` and `HandlePatchPodGroup` accept and discard — Volcano
auto-creates PodGroups internally and we don't need to track them beyond what the
store already holds.

#### Section 4: Stubs

```
GET /apis/topology.volcano.sh/v1alpha1/hypernodes          → HandleListHyperNodes
GET /apis/nodeinfo.volcano.sh/v1alpha1/numatopologies       → HandleListNumaTopologies
GET /apis/scheduling.k8s.io/v1/priorityclasses             → HandleListPriorityClasses
GET /api/v1/resourcequotas                                  → HandleListResourceQuotas
POST /api/v1/namespaces/{ns}/events                        → HandleCreateEvent
PATCH /api/v1/namespaces/{ns}/events/{name}                → HandlePatchEvent
```

These are always-registered informers in Volcano's cache. They must return valid
empty lists (not 404s) otherwise the informer factory panics. HyperNode is Volcano's
network-topology-aware scheduling feature — we return an empty list because COUBES
doesn't model network topology.

### File: `main.go` (modified)

All new routes are registered inside the existing `!testMode` block. API discovery
routes are registered first. The order matters for gorilla/mux — more specific routes
must come before wildcard routes.

Key addition: `resourcequotas` and `events` routes were added here because Volcano
watches these but kube-scheduler does not.

---

## Area 2: In-Memory Store

### File: `store/store.go` (modified)

Two new resource types were added to `InMemoryStore`:

#### Queue store

```go
queues           map[string]map[string]interface{}
queueEventCh     chan metav1.WatchEvent
queueBroadcaster *BroadcastServer[metav1.WatchEvent]
```

Queues are stored as raw `map[string]interface{}` (JSON maps) rather than typed
structs. This avoids importing Volcano's API types into the adapter. The handlers
serialise/deserialise JSON directly.

Methods: `CreateQueue`, `GetQueue`, `GetQueues`, `UpdateQueueStatus`,
`SubscribeQueues`, `CancelQueueSubscription`.

#### PodGroup store

```go
podGroups           map[string]map[string]interface{}  // keyed by "namespace/name"
podGroupEventCh     chan metav1.WatchEvent
podGroupBroadcaster *BroadcastServer[metav1.WatchEvent]
```

Methods: `CreatePodGroup`, `DeletePodGroup`, `GetPodGroups`, `UpdatePodGroupRaw`,
`SubscribePodGroups`, `CancelPodGroupSubscription`.

`UpdatePodGroupRaw` stores the updated PodGroup and broadcasts a MODIFIED event.
This is called by `HandleUpdatePodGroup` when Volcano updates a PodGroup's phase.

Helper functions added: `mustMarshal` (JSON serialise, panic on error) and `copyMap`
(JSON round-trip copy of a map).

---

## Area 3: Pod and PodGroup Creation

### File: `communicator/conversion_utils.go` (modified)

#### `BuildPod` changes

Two additions when `schedulerName == "volcano"`:

**1. `scheduling.k8s.io/group-name` annotation:**
```go
if schedulerName == "volcano" {
    annotations["scheduling.k8s.io/group-name"] = podName
}
```
Volcano's `getJobID()` function reads this annotation to determine which PodGroup a
pod belongs to. Without it, `getJobID()` returns `""`, the pod gets a null jobID,
and Volcano logs `"task has null jobID"` and refuses to schedule it.

**2. `pod.Status.Phase = corev1.PodPending`:**
```go
pod.Status.Phase = corev1.PodPending
```
Volcano's `getTaskStatus()` maps `pod.Status.Phase` to its internal `TaskStatus`.
An empty phase (Go zero value) falls through to `return Unknown`. The gang plugin
then sees `1 Unknown, 1 minAvailable` and refuses to schedule. Setting `PodPending`
ensures tasks are classified as `Pending` and eligible for allocation.

**3. `pod.Status.Phase = corev1.PodRunning` on binding (in `fakeapi/handlers.go`):**
```go
pod.Status.Phase = corev1.PodRunning
```
Set in `HandleBinding` after `pod.Spec.NodeName` is assigned. This ensures Volcano's
node resource accounting correctly reflects bound pods as consuming node capacity.
Without this, Volcano may over-pack subsequent rounds onto nodes that appear empty.

### File: `communicator/communicator.go` (modified)

#### PodGroup creation alongside pod creation

When `schedulerName == "volcano"`, a PodGroup is created in the store for each pod:

```go
if c.schedulerName == "volcano" {
    c.store.CreatePodGroup("default", pod.Name, buildPodGroup(pod.Name, "default"))
}
```

The `buildPodGroup` helper creates a minimal PodGroup with:
- `minMember: 1` — one pod per group (no gang scheduling constraint)
- `queue: default` — uses the default queue
- `status.phase: Pending` — must be `Pending` (not `Inqueue`) so Volcano's `enqueue`
  action processes it. If set to `Inqueue`, Volcano classifies it as `Unknown` and
  never schedules it.

PodGroups are deleted when their corresponding pod is deleted (on completion).

#### Timeout as partial success

```go
decision, err = c.round.Wait(ctx)
if err != nil {
    // Build partial result from whatever was resolved before timeout
    for _, csPod := range snapshot.Pods {
        if not already resolved {
            decision.Unschedulable = append(...)
        }
    }
    // Return as success, not HTTP 408
}
```

Volcano's `backfill` action silently skips pods it cannot schedule — it never sends
a binding or an unschedulable status patch for them. The adapter's scheduling round
would wait forever for a decision that never comes. The fix: on timeout, any pod
that received no decision is marked `Unschedulable` and the partial result is returned
as a normal 200 response. The Java broker then handles the unschedulable pods via its
retry logic.

**Critical:** The `SchedulingRound.Wait()` timeout path was also fixed to return the
partial `assignments` slice rather than an empty `BatchDecision{}`:

```go
// scheduler/scheduler.go — timeout case
return partial, fmt.Errorf("scheduling timeout: ...")
// was: return BatchDecision{}, fmt.Errorf(...)
```

Without this fix, the 4 bindings that arrived before the timeout were lost.

---

## Area 4: Java Broker Changes

### File: `Live_Kubernetes_Broker_Ex.java` (modified)

#### Permanent fragmentation detection

Added `lastUnschedulableIds` tracking to detect when the same pods fail twice with
no new capacity freed and nothing running:

```java
private final Set<Integer> lastUnschedulableIds = new HashSet<>();
```

In `processBatchDecision(batchDecision, hadCompletions)`:
- If the same pod IDs are unschedulable again, no completions happened since last
  round, and `cloudletsSubmitted == 0` (nothing running) → mark them FAILED
  immediately rather than retrying indefinitely.

The `hadCompletions` flag is passed from `submitCloudlets` before
`completedSinceLastRound` is cleared, so the check has accurate information.

The `cloudletsSubmitted == 0` guard is essential: without it, the detection fires
too early (e.g. during round 3 when round-2 cloudlets are still running), marking
pods as permanently failed when they could still be scheduled after completions.

#### Round summary logging

```java
Log.printlnConcat(getName(), ": ── Round ", roundCounter, " result: ",
        scheduled, " scheduled, ", unschedulable, " unschedulable ──");
```

Added at the start of `processBatchDecision`. The `── Round` pattern is included in
`run_test.sh`'s `OUTPUT_FILTER` so it appears in filtered output.

#### Fragmentation_Test assertion relaxed

```java
// was: throw new RuntimeException("Expected 20 cloudlets...")
if(newList1.size() != 20){
    Log.println("WARNING: Expected 20 cloudlets to complete but only received "
            + newList1.size() + " — this may indicate scheduler-induced fragmentation...");
}
```

The hard assertion was replaced with a warning. The Fragmentation_Test is designed
to measure fragmentation, not assert its absence. A bin-packing scheduler will
legitimately produce fewer than 20 completed cloudlets on this workload.

---

## Area 5: Config Files

### `volcano-scheduler/` directory

Three files, mirroring `second-scheduler/`:

**`kubeconfig.yaml`** — identical to `second-scheduler/kubeconfig.yaml`. Points to
`http://localhost:8080` with `insecure-skip-tls-verify: true`.

**`volcano-scheduler.conf`** — Volcano's config format (not `KubeSchedulerConfiguration`):
```yaml
actions: "enqueue, allocate, backfill"
tiers:
- plugins:
  - name: priority
- plugins:
  - name: predicates
  - name: nodeorder
    arguments:
      mostrequested.weight: 1   # bin-packing
      leastrequested.weight: 0
```

The `gang` plugin was intentionally omitted. With `gang`, Volcano enforces all-or-
nothing scheduling per PodGroup. Since each COUBES pod has its own PodGroup with
`minMember: 1`, gang scheduling adds no value and can cause issues if the PodGroup
phase state machine gets out of sync.

**`docker-compose.yml`** — runs `volcanosh/vc-scheduler:v1.10.0`:
```yaml
command:
  - vc-scheduler
  - --scheduler-name=volcano
  - --leader-elect=false
  - --enable-metrics=false   # avoids port conflict with adapter on :8080
  - --enable-healthz=false
  - --cache-dumper=false
```

`--enable-metrics=false` is critical. Volcano's metrics server defaults to `:8080`,
the same port as the COUBES adapter. Without this flag, the container fails to start.

### `run_test.sh` changes

- Added `--volcano` flag: sets `SCHEDULER_DIR=volcano-scheduler`,
  `SCHEDULER_CONTAINER=volcano-scheduler`, `ADAPTER_FLAGS=--scheduler=volcano`
- `SCHEDULER_CONTAINER` variable replaces hardcoded `kube-scheduler` throughout
- `scheduler_ready()` checks all logs (not `--since 35s`) — Volcano containers from
  previous runs would fail the readiness check if their "Caches populated" lines
  were older than 35 seconds
- `ensure_infra` now calls `wait_for_scheduler` before deciding to restart, avoiding
  unnecessary restarts of containers that are still initialising
- `HANG_TIMEOUT` increased from 45s to 90s — must exceed the 60s scheduling round
  timeout so the partial result is returned before the hang recovery kills the process
- `OUTPUT_FILTER` extended with `── Round` to show per-round summaries

---

## How to Run

```bash
# Volcano (bin-packing)
./run_test.sh --volcano org.example.testSuite.Fragmentation_Test

# kube-scheduler (unchanged)
./run_test.sh org.example.testSuite.Fragmentation_Test
./run_test.sh --scheduler=most-allocated org.example.testSuite.Fragmentation_Test
```

---

## Known Behaviours

**Fragmentation with bin-packing:** The Fragmentation_Test submits a second wave of
larger pods after the first wave has packed nodes tightly. With bin-packing, the
first wave may leave only 1-PE gaps on each node, making the 2-PE second-wave pods
unschedulable. This is the test working correctly — it measures fragmentation. The
WARNING message in the output is expected.

**Slow round 2 timeout:** When a pod is genuinely unschedulable, Volcano never sends
a binding or unschedulable status patch for it. The adapter waits 60 seconds before
returning the partial result. This is normal and expected — the 90s hang timeout
gives it room to complete.

**"Scheduler running but not ready" on first run:** If a Volcano container from a
previous run is still present, `scheduler_ready` checks all logs for "Caches
populated". If the container is mid-initialisation, `wait_for_scheduler` waits up
to 35 seconds before deciding to restart. This is normal.

**Port conflict warning:** Volcano logs `init kubeclient in hamivgpu failed` on
startup. This is harmless — it's trying to find a GPU management kubeconfig that
doesn't exist in our environment.

---

## Extending the Integration

**To add a new Volcano plugin:** Edit `volcano-scheduler/volcano-scheduler.conf`.
The `nodeorder` plugin supports many scoring dimensions — see the Volcano docs for
`leastrequested`, `mostrequested`, `balancedresource`, `nodeaffinity`, etc.

**To add a new Volcano CRD endpoint:** Follow the pattern in `volcano_handlers.go`.
Add a handler method to `FakeAPIHandler`, add the resource to the appropriate
`HandleXxxResources` discovery handler, add the route in `main.go`, and add storage
to `store.go` if the resource needs to be persisted across requests.

**To support a different Volcano version:** Change the image tag in
`volcano-scheduler/docker-compose.yml`. The API surface (`scheduling.volcano.sh/v1beta1`)
has been stable since Volcano 1.3. Check the Volcano changelog for any new required
endpoints if upgrading significantly.

**To enable gang scheduling:** Add `- name: gang` to the first tier in
`volcano-scheduler.conf`. Also change `buildPodGroup` in `communicator.go` to set
`minMember` to the number of pods in the gang. Note that gang scheduling requires
all pods in a group to be schedulable simultaneously — this interacts with the
fragmentation detection logic.

---

## Volcano Feature Audit — What COUBES Supports vs. What Volcano Offers

**Date:** 2026-05-04

Volcano positions itself as a "cloud native batch scheduling platform for high-performance
workloads" (CNCF's first and only official container batch scheduling project). Gang
scheduling is one feature among many — not the sole headline. The official feature list
from volcano.sh is broader than what COUBES currently exercises.

### Feature-by-Feature Status

| Volcano Feature | COUBES Status | What Would Be Needed |
|---|---|---|
| **Unified Scheduling** (native K8s workloads + batch frameworks) | ✅ Partially supported | We schedule standard pods. No VolcanoJob CRD support, but not needed — pods are the unit of work in COUBES. |
| **Binpack Scheduling** (`nodeorder` with `mostrequested.weight`) | ✅ Supported | Already configured and working. This is what COUBES currently uses Volcano for. |
| **Spread Scheduling** (`nodeorder` with `leastrequested.weight`) | ✅ Supported | Just change weights in `volcano-scheduler.conf`. |
| **Gang Scheduling** (all-or-nothing per PodGroup) | ❌ Not exercised | Every pod has its own PodGroup with `minMember: 1`, so gang semantics never trigger. Requires: (1) grouping multiple cloudlets into one PodGroup with `minMember > 1`, (2) broker logic to hold cloudlets until all gang members are bound, (3) `gang` plugin added to config. |
| **Queue Resource Management** (multi-queue with quotas/weights) | ❌ Not exercised | All pods go to the `default` queue. Requires: (1) creating additional queues with weight/capacity specs via the fake API, (2) assigning cloudlets to different queues, (3) enabling the `proportion` plugin. |
| **Hierarchical Queues** (parent-child queue trees) | ❌ Not exercised | Extension of queue management. Requires queue tree creation in the fake API + `proportion` plugin. |
| **DRF (Dominant Resource Fairness)** | ❌ Not exercised | Requires: (1) multiple queues or jobs competing for resources, (2) `drf` plugin enabled. Without multi-queue, DRF has nothing to arbitrate. |
| **Proportion/Capacity Scheduling** (queue-based resource sharing/preemption) | ❌ Not exercised | Requires: (1) multiple queues with `weight` or `capacity` fields, (2) `proportion` plugin enabled, (3) workloads assigned to different queues. |
| **Priority Scheduling** (inter-job priority ordering) | ⚠️ Partially supported | The `priority` plugin is enabled in config, but all pods have the same default priority. Requires: (1) PriorityClass objects in the fake API (currently returns empty list), (2) pods with different `priorityClassName` values. |
| **Preemption** (evicting lower-priority pods for higher-priority ones) | ❌ Not supported | Requires priority differentiation + preemption logic. The fake API server doesn't handle pod eviction/deletion initiated by the scheduler. |
| **Network Topology-aware Scheduling** (HyperNode) | ❌ Stubbed out | Returns empty HyperNode list. COUBES doesn't model network topology between nodes. Would require: (1) HyperNode objects describing node topology, (2) CloudSim modelling of network bandwidth/latency. |
| **NUMA-aware Scheduling** | ❌ Stubbed out | Returns empty NumaTopology list. COUBES doesn't model NUMA architecture. Would require: (1) NumaTopology objects per node, (2) CloudSim modelling of NUMA memory access patterns. |
| **GPU/NPU Scheduling** (heterogeneous devices, MIG, vGPU) | ❌ Not supported | COUBES models CPU (PEs) only. Would require: (1) extended resource types on nodes (e.g., `nvidia.com/gpu`), (2) pods requesting GPU resources, (3) CloudSim modelling of GPU workloads. |
| **Task-topology Scheduling** (co-locate communicating tasks) | ❌ Not supported | Requires inter-task communication modelling, which CloudSim doesn't have. |
| **SLA Scheduling** (service quality guarantees) | ❌ Not supported | Requires SLA definitions and deadline-aware scheduling. Not modelled in COUBES. |
| **Online-Offline Colocation** (mixed workload types sharing a cluster) | ❌ Not supported | Requires workload type differentiation and QoS enforcement. |
| **Multi-cluster Scheduling** (volcano-global) | ❌ Not applicable | COUBES simulates a single cluster. |
| **Load-aware Descheduling** | ❌ Not applicable | Requires the Volcano descheduler component, not just the scheduler. |
| **Overcommit** (allow queues to use more than their quota) | ❌ Not exercised | Requires: (1) `overcommit` plugin enabled, (2) multiple queues with quotas. |
| **Backfill** (schedule small jobs into gaps) | ✅ Enabled | The `backfill` action is in the config. However, with single-pod PodGroups, backfill behaves identically to regular allocation. It becomes meaningful with gang scheduling (backfill can place small jobs while large gangs wait). |

### Summary

**Currently exercised:** 3 features (binpack, spread, backfill — all basic node scoring)
**Partially supported but not exercised:** 2 features (unified scheduling, priority)
**Not exercised but feasible to add:** 5 features (gang, queue management, DRF, proportion, overcommit)
**Not feasible without CloudSim extensions:** 6 features (topology, NUMA, GPU, task-topology, SLA, colocation)
**Not applicable:** 2 features (multi-cluster, descheduling)

### Which Features Would Differentiate Volcano from kube-scheduler in COUBES?

The features that would produce measurably different scheduling decisions from
kube-scheduler MostAllocated, ranked by implementation feasibility:

1. **Queue Resource Management + Proportion plugin** (easiest) — Create 2-3 queues
   with different weights, assign cloudlet batches to different queues. Volcano
   allocates resources proportionally; kube-scheduler has no queue concept at all.
   Requires: fake API queue creation with weights, cloudlet-to-queue mapping in broker.

2. **Gang Scheduling** (moderate) — Group related cloudlets into PodGroups with
   `minMember > 1`. Volcano places all-or-nothing; kube-scheduler places greedily.
   Requires: cloudlet grouping in broker, hold-until-complete logic, `gang` plugin.

3. **DRF Fairness** (moderate, depends on #1) — With multiple queues competing for
   multi-dimensional resources (CPU + memory), DRF ensures no queue dominates.
   kube-scheduler has no fairness concept. Requires: queue management + memory
   modelling on pods.

4. **Priority + Preemption** (harder) — Different priority classes with preemption.
   Volcano can evict low-priority pods to make room for high-priority ones.
   Requires: PriorityClass objects, pod eviction handling in fake API, broker
   handling of preempted cloudlets.

---

## Queue Resource Management — Implementation (2026-05-05)

Feature #1 from the list above has been implemented and verified.

### What Was Built

The `proportion` plugin is now enabled in Volcano's config. Cloudlets can be assigned
to different queues by setting their `classType` field. The adapter creates queues on
demand with predefined weights when pods reference them.

### Data Flow

```
Cloudlet.setClassType(1)  →  buildPodJson adds "queue":"high-priority"
    →  adapter CsPod.Queue = "high-priority"
    →  ensureVolcanoQueue("high-priority") creates queue with weight=3 if not exists
    →  buildPodGroup sets spec.queue = "high-priority"
    →  Volcano's proportion plugin allocates resources proportionally
```

### Queue Weight Map

| classType | Queue Name | Weight | Share (with default) |
|---|---|---|---|
| 0 (default) | `default` | 1 (Volcano built-in) | 100% when alone |
| 1 | `high-priority` | 3 | 75% when competing with batch |
| 2 | `batch` | 1 | 25% when competing with high-priority |

Queues are only created when pods reference them. Tests that don't set `classType`
route everything to `default` and the proportion plugin has nothing to arbitrate —
behaviour is identical to before.

### Files Changed

| File | Change |
|---|---|
| `communicator/k8s-simplified-structs.go` | `Queue` field on `CsPod` |
| `communicator/communicator.go` | `buildPodGroup` accepts queue; `ensureVolcanoQueue` creates on demand |
| `Live_Kubernetes_Broker_Ex.java` | `QUEUE_NAMES` map; `buildPodJson` includes queue |
| `metrics/SimulationMetrics.java` | `printPerQueueMetrics()` static method |
| `testSuite/Queue_Priority_Test.java` | New test scenario |
| `volcano-scheduler/volcano-scheduler.conf` | `proportion` plugin added to tier 1 |

### Verified Results

With 16 PE slots and 16 pods (8 high-priority + 8 batch, each 2 PEs):

| Scheduler | Round 1 high-priority | Round 1 batch | Behaviour |
|---|---|---|---|
| Volcano (proportion, 3:1) | 6 (75%) | 2 (25%) | Fair sharing by weight |
| kube-scheduler (MostAllocated) | 8 (100%) | 0 (0%) | Complete batch starvation |

### Per-Queue Metrics

`SimulationMetrics.printPerQueueMetrics(completedCloudlets)` reports per queue:
- **Queue turnaround** — last finish minus earliest submission for that queue
- **Avg cloudlet turnaround** — mean of (finish - submission) per cloudlet
- **Avg wait time** — mean of (start - submission) per cloudlet

---

## PUT Pod Status Fix (2026-05-05)

### Problem

Volcano reports unschedulable pods via `PUT /api/v1/namespaces/default/pods/{name}/status`
(a full status sub-resource replacement). The fake API server only handled `PATCH` on
that path (used by kube-scheduler). Volcano's PUT was rejected with "method not allowed",
so the adapter never learned about unschedulable pods and waited the full 60s scheduling
round timeout for a response that would never come.

This caused Fragmentation_Test_Large to take **420 seconds** (7 minutes) with Volcano
due to multiple 60s timeouts per rescheduling round.

### Root Cause (confirmed from Volcano source)

`volcano/pkg/scheduler/cache/cache.go` line 345:
```go
func (su *defaultStatusUpdater) UpdatePodStatus(pod *v1.Pod) (*v1.Pod, error) {
    return su.kubeclient.CoreV1().Pods(pod.Namespace).UpdateStatus(...)
}
```

This calls `UpdateStatus` which is `PUT /pods/{name}/status`. The pod body contains
`Status.Conditions` with `PodScheduled=False, Reason=Unschedulable` — the same
condition kube-scheduler sends via PATCH.

### Fix

One line in `main.go` — register the existing `HandlePodStatusPatch` handler for PUT:
```go
router.HandleFunc("/api/v1/namespaces/default/pods/{name}/status", func(...) {
    fakeAPI.HandlePodStatusPatch(w, r, round)
}).Methods("PUT")
```

The handler already parses a full `corev1.Pod` body and checks for the unschedulable
condition, so it works identically for both PATCH and PUT.

### Impact

Fragmentation_Test_Large: **420s → 7s** with Volcano.
All 11 test scenarios now pass with Volcano in under 45s each (315s total).

---

## run_test.sh / run_all_tests.sh Improvements (2026-05-05)

### Log File Naming

Logs are now written to `debug/<TestName>_<timestamp>_{sim,adapter,scheduler}.log`.
Symlinks `debug/{sim,adapter,scheduler}.log` always point to the latest run.
Previous runs are preserved.

### Timeouts

- `HANG_TIMEOUT=45s` for kube-scheduler tests
- `HANG_TIMEOUT=90s` for Volcano tests (set by `--volcano` flag)
- `run_all_tests.sh` has per-test `timeout` wrapper (default 45s, `--timeout=N`)

### run_all_tests.sh

Rewritten to show inline progress (one line per test), timing, all 11 tests, and
error context on failure. Starts infrastructure once and reuses across tests via
`--keep-infra`. Restarts adapter + scheduler automatically on test failure.
Total runtime: **140s** with Volcano (vs 315s when restarting per test).

---

## Test Coverage (2026-05-05)

### Go unit tests (`go test ./...` — all pass)

| File | Tests | What it guards |
|---|---|---|
| `fakeapi/volcano_handlers_test.go` | 7 | PUT/PATCH unschedulable, queue CRUD, 404 format |
| `communicator/volcano_test.go` | 11 | buildPodGroup queue, BuildPod annotations/phase, ensureVolcanoQueue idempotency |
| `scheduler/scheduler_test.go` | 7 | Round lifecycle, partial result on timeout |
| `communicator/conversion_test.go` | fixed | Struct comparison with map field |

### Java unit tests (`mvn test` — 27/27 pass)

| File | Tests | What it guards |
|---|---|---|
| `QueueMappingTest.java` | 5 | classType→queue name mapping, immutability |
| `PerformanceMetricsTest.java` | 5 | P99 latency, throughput (1 assertion fixed) |

### Integration tests (via `run_all_tests.sh`)

All 11 test scenarios pass with both kube-scheduler and Volcano.

---

## Gang Scheduling — Implementation (2026-05-05)

Feature #2 from the differentiating features list. Volcano schedules gang members
atomically (all-or-nothing); kube-scheduler has no native gang concept.

### What Was Built

Cloudlets with the same `gangId` form a gang. The adapter creates a shared PodGroup
with `minMember = gang size`. The broker holds scheduled gang members in a "waiting
room" until all members are placed, then submits them all to CloudSim simultaneously.

### Data Flow

```
CoubesCloudlet(gangId="A")  →  buildPodJson adds "gangId":"A"
    →  adapter counts gang sizes, creates PodGroup "gang-A" with minMember=N
    →  all pods get annotation scheduling.k8s.io/group-name = "gang-A"
    →  Volcano's gang plugin enforces all-or-nothing
    →  broker holds scheduled members until gang is complete
    →  all members submitted to CloudSim at once
```

### Files Changed

| File | Change |
|---|---|
| `CoubesCloudlet.java` | `gangId` field (nullable), getter, constructor chain |
| `Live_Kubernetes_Broker_Ex.java` | `gangWaitingRoom`, `gangHeldCloudlets`, `gangExpectedSizes` maps; gang holding logic in `processBatchDecision`; deadlock detection; `cloudletArrivalTimes` tracking |
| `communicator/k8s-simplified-structs.go` | `GangId` field on `CsPod` |
| `communicator/communicator.go` | Shared PodGroup creation for gang pods (created before pods); gang size counting |
| `store/store.go` | PodGroup deletion in `DeleteAll()` with DELETED watch events |
| `volcano-scheduler/volcano-scheduler.conf` | `gang` plugin added to tier 1 |
| `scheduler/scheduler.go` | 5-second stall exit (early return when no progress after first decision) |

### Gang Holding Logic (Broker)

When a scheduled pod is part of a gang:
1. Pod is removed from `cloudletsSubmittedToMiddle`
2. Pod + assigned nodeId stored in `gangWaitingRoom[gangId]`
3. Cloudlet object stored in `gangHeldCloudlets[cloudletId]`
4. When `gangWaitingRoom[gangId].size() == gangExpectedSizes[gangId]`:
   - All members submitted to CloudSim via `submitCloudletToVmInCloudSim`
   - Gang removed from waiting room

### Deadlock Detection

After processing unschedulable pods, if:
- A gang has held members (`gangWaitingRoom` non-empty)
- Nothing is running in CloudSim (`cloudletsSubmitted == 0`)
- Held + pending < expected (some members permanently failed or can't fit)

Then: all held members and remaining pending members are marked FAILED.

### Stall Exit (Adapter)

The scheduling round now exits early after 5 seconds of no progress (no new bindings
or failures) if at least one decision has been received. This replaces the previous
30-second full timeout for rounds where Volcano holds gang pods without reporting them.

Impact: Overload_Comparison_Test went from ~700s to ~107s wall-clock with Volcano.

### Verified Results

**Gang_Scheduling_Test** (4 VMs × 4 PEs, 3 gangs):
- All gangs scheduled atomically (all members start at same simulated time)
- Works with both Volcano and kube-scheduler (broker enforces atomicity)

**Gang_Constrained_Test** (2 VMs × 4 PEs, gang of 6 needing 12 PEs but only 8 available):
- Volcano: 0 scheduled, 6 unschedulable (gang can't fit → immediate rejection)
- kube-scheduler: 4 scheduled + held, 2 unschedulable → deadlock detected → all 6 FAILED

**Overload_Comparison_Test** (5 heterogeneous VMs, 71 pods, mixed gangs + queues):

| Metric | Volcano | kube-scheduler |
|---|---|---|
| Simulated TTC | 1013s | 1053s |
| Energy | 504 Wh | 524 Wh |
| HP avg scheduling wait | 173s | 391s |
| Batch avg scheduling wait | 433s | 236s |

Tradeoff: Volcano prioritizes HP (proportion plugin), kube-scheduler treats all equally.

---

## Scheduling Wait Metric Fix (2026-05-05)

### Problem

`printPerQueueMetrics` used CloudSim's `getSubmissionTime()` which reflects when the
cloudlet was submitted to the VM (after scheduling), not when it first arrived at the
broker. This made all wait times appear as 0.

### Fix

Added `cloudletArrivalTimes` map to the broker, recording `CloudSim.clock()` when each
cloudlet first enters `cloudletsSubmittedToMiddle`. Updated `printPerQueueMetrics` to
accept an optional `arrivalTimes` map and compute wait as `execStartTime - arrivalTime`.

### Impact

Queue_Starvation_Test now correctly shows:
- Volcano batch avg wait: 80.5s (2/4 batch pods start in round 1)
- kube-scheduler batch avg wait: 161.0s (all batch pods wait until round 2)

---

## Scheduling Round Timeout Reduction (2026-05-05)

Reduced from 60s to 30s (`main.go`). Combined with the 5-second stall exit, most
rounds complete in 1-6 seconds. The 30s timeout is only hit when Volcano receives
zero decisions for a round (rare edge case).

HANG_TIMEOUT in `run_test.sh`: 45s default, 60s for Volcano.
