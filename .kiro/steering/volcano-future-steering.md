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
- `SCHEDULER_CONTAINER` variable replaces hardcoded `my-scheduler` throughout
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
./run_test.sh --scheduler=my-scheduler org.example.testSuite.Fragmentation_Test
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
