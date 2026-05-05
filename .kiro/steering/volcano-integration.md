# Volcano Scheduler Integration with COUBES

## Overview

This document captures the findings from analysing the Volcano scheduler source code
(`/home/fshir/COUBES/volcano`) and assesses what is required to integrate it into COUBES
in the same way the Kubernetes default scheduler is integrated via `second-scheduler/`.

**Verdict: Feasible, but requires more work than adding a second kube-scheduler profile.**
The core scheduling loop is compatible with the fake API server pattern, but Volcano
introduces several Volcano-specific CRD API groups and a mandatory startup sequence
(Queue creation) that the fake API server does not currently handle.

---

## How Volcano Works (Relevant to COUBES)

### Entry point and flags

Binary: `vc-scheduler` (built from `cmd/scheduler/main.go`)

Key startup flags:
```
--kubeconfig        path to kubeconfig (same format as kube-scheduler)
--master            API server address (alternative to kubeconfig)
--scheduler-name    pod schedulerName to watch (default: "volcano")
--scheduler-conf    path to Volcano config file (actions/tiers YAML)
--leader-elect=false required to run as a single instance
--default-queue     name of the default queue (default: "default")
--enable-metrics=false  disable Prometheus metrics server (simplifies setup)
--enable-healthz=false  disable health check server (simplifies setup)
--v=3               log verbosity
```

### Configuration format

Volcano does NOT use `KubeSchedulerConfiguration`. It uses its own YAML format:

```yaml
actions: "enqueue, allocate, backfill"
tiers:
- plugins:
  - name: priority
  - name: gang
  - name: conformance
- plugins:
  - name: overcommit
  - name: drf
  - name: predicates
  - name: proportion
  - name: nodeorder
```

This is the built-in default. A minimal config for COUBES (no gang scheduling, just
node-order scoring equivalent to kube-scheduler) would be:

```yaml
actions: "enqueue, allocate, backfill"
tiers:
- plugins:
  - name: priority
- plugins:
  - name: predicates
  - name: nodeorder
```

### Scheduling loop

Volcano's scheduling loop (`pkg/scheduler/scheduler.go`) runs `runOnce()` periodically
(default: every 1 second). Each cycle:
1. Opens a session (`framework.OpenSession`)
2. Executes each configured action in order (`enqueue`, `allocate`, `backfill`)
3. Closes the session

The `allocate` action (`pkg/scheduler/actions/allocate/allocate.go`) picks pending pods
grouped into **Jobs** (by PodGroup), scores nodes, and calls `stmt.Allocate(task, node)`.

### Binding mechanism

Volcano uses the **standard Kubernetes binding API**, identical to kube-scheduler:

```go
// pkg/scheduler/cache/cache.go — DefaultBinder.Bind()
db.kubeclient.CoreV1().Pods(p.Namespace).Bind(context.TODO(),
    &v1.Binding{
        ObjectMeta: metav1.ObjectMeta{Namespace: p.Namespace, Name: p.Name, ...},
        Target: v1.ObjectReference{Kind: "Node", Name: task.NodeName},
    },
    metav1.CreateOptions{})
```

This maps to `POST /api/v1/namespaces/{namespace}/pods/{name}/binding` — the **same
endpoint** the fake API server already handles. No changes needed for binding.

### Pod grouping: PodGroup and Queue

This is the key difference from kube-scheduler. Volcano groups pods into **Jobs** via
the `PodGroup` CRD. Every pod that Volcano schedules must belong to a PodGroup.

On startup, Volcano **immediately tries to create** a `default` queue and a `root` queue
via the Volcano CRD client (`pkg/scheduler/cache/cache.go — newDefaultAndRootQueue()`):

```go
vcClient.SchedulingV1beta1().Queues().Get(...)   // check if exists
vcClient.SchedulingV1beta1().Queues().Create(...) // create if not
```

This happens **before** any scheduling. If the fake API server returns errors on these
calls, Volcano will retry indefinitely (60 retries × 1s) and never start scheduling.

---

## API Endpoints Volcano Requires

### Standard Kubernetes API (already handled by fake API server)

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/nodes` (+ watch) | Node list/watch |
| `GET /api/v1/pods` (+ watch) | Pod list/watch |
| `GET /api/v1/namespaces` | Namespace list |
| `GET /api/v1/services` | Services (for affinity plugins) |
| `GET /api/v1/persistentvolumes` | PV list |
| `GET /api/v1/persistentvolumeclaims` | PVC list |
| `GET /api/v1/replicationcontrollers` | RC list (WorkLoadSupport feature) |
| `GET /apis/apps/v1/replicasets` | ReplicaSet list |
| `GET /apis/apps/v1/statefulsets` | StatefulSet list |
| `GET /apis/storage.k8s.io/v1/storageclasses` | StorageClass list |
| `GET /apis/storage.k8s.io/v1/csinodes` | CSINode list (actively watched) |
| `GET /apis/storage.k8s.io/v1/volumeattachments` | VolumeAttachment list |
| `GET /apis/policy/v1/poddisruptionbudgets` | PDB list (PDB feature) |
| `GET /apis/scheduling.k8s.io/v1/priorityclasses` | PriorityClass list |
| `GET /api/v1/resourcequotas` | ResourceQuota list |
| `POST /api/v1/namespaces/default/pods/{name}/binding` | **Bind pod to node** ✓ |
| `GET /api/v1/events` / `POST /api/v1/namespaces/.../events` | Event recording |

### Volcano-specific CRD API (NOT currently handled)

These are the new endpoints that must be added to the fake API server:

| Endpoint | Group | Purpose | Priority |
|---|---|---|---|
| `GET /apis/scheduling.volcano.sh/v1beta1/queues` | scheduling.volcano.sh | Queue list/watch | **Critical** — queried on startup |
| `POST /apis/scheduling.volcano.sh/v1beta1/queues` | scheduling.volcano.sh | Create default+root queues | **Critical** — called on startup |
| `GET /apis/scheduling.volcano.sh/v1beta1/queues/{name}` | scheduling.volcano.sh | Get specific queue | **Critical** |
| `PUT /apis/scheduling.volcano.sh/v1beta1/queues/{name}/status` | scheduling.volcano.sh | Update queue status | Required |
| `GET /apis/scheduling.volcano.sh/v1beta1/podgroups` | scheduling.volcano.sh | PodGroup list/watch | **Critical** — pods grouped here |
| `POST /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups` | scheduling.volcano.sh | Create PodGroup | Required |
| `PUT /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups/{name}` | scheduling.volcano.sh | Update PodGroup status | Required |
| `GET /apis/topology.volcano.sh/v1alpha1/hypernodes` | topology.volcano.sh | HyperNode list/watch | Required (always registered) |
| `GET /apis/nodeinfo.volcano.sh/v1alpha1/numatopologies` | nodeinfo.volcano.sh | NUMA topology (feature-gated) | Optional |
| `GET /apis/shard.volcano.sh/v1alpha1/nodeshards` | shard.volcano.sh | Node sharding (feature-gated) | Optional |

### API discovery endpoints (needed for client initialisation)

The Volcano client (`vcclient`) will also call API discovery:
- `GET /apis` — list all API groups
- `GET /apis/scheduling.volcano.sh` — group info
- `GET /apis/scheduling.volcano.sh/v1beta1` — resource list for this group
- `GET /apis/topology.volcano.sh/v1alpha1` — resource list
- (similar for nodeinfo, shard groups)

The fake API server currently returns a minimal `/apis` response. It must be extended
to advertise the Volcano groups.

---

## Key Differences vs. kube-scheduler Integration

| Aspect | kube-scheduler | Volcano vc-scheduler |
|---|---|---|
| Docker image | `registry.k8s.io/kube-scheduler:v1.33.0` | `volcanosh/vc-scheduler:v1.10.0` |
| Config format | `KubeSchedulerConfiguration` YAML | Volcano actions/tiers YAML |
| Config flag | `--config` | `--scheduler-conf` |
| Scheduler name | `default-scheduler` / `my-scheduler` | `volcano` (default) |
| Pod grouping | None (individual pods) | PodGroup CRD (mandatory) |
| Queue concept | None | Queue CRD (mandatory, created on startup) |
| Extra CRD groups | None | `scheduling.volcano.sh`, `topology.volcano.sh`, `nodeinfo.volcano.sh`, `shard.volcano.sh` |
| Binding API | `POST .../binding` | `POST .../binding` (same ✓) |
| Leader election flag | `--leader-elect=false` | `--leader-elect=false` |
| Metrics port | 10259 (secure) | 8080 (default, conflicts with adapter!) |

**Important port conflict:** Volcano's `--listen-address` defaults to `:8080`, which is
the same port the COUBES adapter uses. Must override with `--listen-address=:18080` (or
disable with `--enable-metrics=false`).

---

## PodGroup Requirement: The Critical Constraint

Volcano groups pods into **Jobs** via PodGroup. When Volcano's cache processes a pod, it
looks up the pod's owning PodGroup. If no PodGroup exists, the pod is treated as a
standalone pod with an auto-generated PodGroup (one pod per group, minAvailable=1).

For COUBES, pods are created individually by the adapter (`cspod-N`). Volcano will
auto-create a PodGroup for each pod if the `podgroup` controller is running — but in
COUBES there is no controller, only the scheduler.

**Two options:**

1. **Let Volcano auto-create PodGroups** (simpler): Volcano creates a PodGroup per pod
   automatically when it first sees a pod without one. The fake API server must handle
   `POST /apis/scheduling.volcano.sh/v1beta1/namespaces/default/podgroups`.

2. **Pre-create PodGroups** (more control): The adapter creates a PodGroup for each pod
   before submitting the pod. This requires adding PodGroup creation to the adapter's
   `HandleSchedulePods` path.

Option 1 is simpler and sufficient for COUBES benchmarking.

---

## What Needs to Be Built

### 1. New fake API handlers (in `fakeapi/handlers.go`)

```
HandleListVolcanoQueues       GET  /apis/scheduling.volcano.sh/v1beta1/queues
HandleGetVolcanoQueue         GET  /apis/scheduling.volcano.sh/v1beta1/queues/{name}
HandleCreateVolcanoQueue      POST /apis/scheduling.volcano.sh/v1beta1/queues
HandleUpdateVolcanoQueueStatus PUT /apis/scheduling.volcano.sh/v1beta1/queues/{name}/status
HandleListVolcanoPodGroups    GET  /apis/scheduling.volcano.sh/v1beta1/podgroups
HandleListVolcanoPodGroupsNS  GET  /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups
HandleCreateVolcanoPodGroup   POST /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups
HandleUpdateVolcanoPodGroup   PUT  /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups/{name}
HandleListHyperNodes          GET  /apis/topology.volcano.sh/v1alpha1/hypernodes
HandleListNumaTopologies      GET  /apis/nodeinfo.volcano.sh/v1alpha1/numatopologies
HandleListNodeShards          GET  /apis/shard.volcano.sh/v1alpha1/nodeshards
```

Queues and PodGroups need watch support (same chunked streaming pattern as nodes/pods).

### 2. Extended API discovery

The `/apis` response must include the Volcano groups. The per-group discovery endpoints
(`/apis/scheduling.volcano.sh`, `/apis/scheduling.volcano.sh/v1beta1`, etc.) must return
valid `APIGroup` and `APIResourceList` responses.

### 3. In-memory Queue store

The fake API server needs a simple in-memory store for Queue objects (similar to the
existing node/pod store) so that:
- Volcano's startup `GET queue/default` returns 404 → triggers `POST` to create it
- Subsequent `GET` returns the created queue
- Watch streams deliver `ADDED` events for the created queues

### 4. `volcano-scheduler/` directory

Mirror of `second-scheduler/` with:
- `kubeconfig.yaml` — identical, points to `http://localhost:8080`
- `volcano-scheduler.conf` — Volcano actions/tiers config
- `docker-compose.yml` — runs `volcanosh/vc-scheduler` with `--listen-address=:18080`

### 5. Adapter `--scheduler` flag

Start the adapter with `--scheduler=volcano` so pods get `spec.schedulerName: volcano`.

---

## Feasibility Assessment

**Overall: Feasible with moderate effort.**

The hard parts are already solved:
- The binding mechanism is identical to kube-scheduler (no changes needed)
- The fake API server architecture already supports watch streams
- The adapter's scheduling round mechanism works for any scheduler that uses the binding API

The new work required:
- ~5–8 new HTTP handlers for Volcano CRD endpoints
- In-memory Queue store (small, ~50 lines)
- Extended API discovery responses
- `volcano-scheduler/` config directory

Estimated effort: **1–2 days** of focused implementation.

The main risk is the PodGroup auto-creation flow — Volcano may behave differently when
PodGroups are missing vs. present. This should be validated by running Volcano against
the adapter with `--v=5` and observing the actual request sequence before implementing
the handlers.

---

## Recommended Implementation Order

1. Add Volcano CRD API discovery to `/apis` response
2. Implement Queue handlers (GET list, GET by name, POST create, watch)
3. Implement PodGroup handlers (GET list, POST create, watch)
4. Implement HyperNode stub (empty list, no watch needed initially)
5. Create `volcano-scheduler/` directory with config files
6. Test end-to-end with `--v=5` and verify pods get scheduled
7. Add nodeinfo/shard stubs only if Volcano logs errors about them

---

## Source Files of Interest

| File | Relevance |
|---|---|
| `volcano/cmd/scheduler/app/options/options.go` | All startup flags |
| `volcano/pkg/scheduler/cache/cache.go` | All informers registered; Queue creation on startup; binding |
| `volcano/pkg/scheduler/scheduler.go` | Main scheduling loop; default config |
| `volcano/pkg/scheduler/actions/allocate/allocate.go` | How pods are assigned to nodes |
| `volcano/pkg/scheduler/util.go` | `DefaultSchedulerConf` — built-in config |
| `volcano/staging/src/volcano.sh/apis/pkg/apis/scheduling/v1beta1/` | Queue and PodGroup types |
| `volcano/staging/src/volcano.sh/apis/pkg/apis/topology/v1alpha1/` | HyperNode type |

---

## Implementation Complete

**Date:** 2026-04-29

All required components have been implemented and verified. The Volcano scheduler can now be run against COUBES using the same pattern as the existing `second-scheduler/` setup.

### What Was Built

#### 1. Queue Store (`store/store.go`)

Added in-memory storage for Volcano Queue CRDs with watch support:
- `queues map[string]map[string]interface{}` — stores Queue objects as raw JSON maps
- `queueEventCh` and `queueBroadcaster` — broadcast ADDED/MODIFIED events to watch streams
- `CreateQueue()`, `GetQueue()`, `GetQueues()`, `UpdateQueueStatus()` — CRUD operations
- `SubscribeQueues()`, `CancelQueueSubscription()` — watch stream management

**Why raw maps?** Avoids importing Volcano's API types into the adapter. The handlers serialise/deserialise JSON directly.

#### 2. Volcano CRD Handlers (`fakeapi/volcano_handlers.go`)

546 lines of new HTTP handlers implementing:

**API Discovery** (required by Volcano's Go client before any resource requests):
- `HandleAPIVersions` — `GET /api` — lists core API versions
- `HandleAPIGroups` — `GET /apis` — lists all API groups (including Volcano groups)
- `HandleCoreV1Resources` — `GET /api/v1` — resource list for core v1
- `HandleSchedulingVolcanoResources` — `GET /apis/scheduling.volcano.sh/v1beta1` — Queue/PodGroup resources
- `HandleTopologyVolcanoResources` — `GET /apis/topology.volcano.sh/v1alpha1` — HyperNode resources
- `HandleNodeinfoVolcanoResources` — `GET /apis/nodeinfo.volcano.sh/v1alpha1` — NumaTopology resources

**Queue CRUD + Watch** (critical — Volcano creates `default` and `root` queues on startup):
- `HandleListQueues` — `GET /apis/scheduling.volcano.sh/v1beta1/queues` — list + watch
- `HandleGetQueue` — `GET /apis/scheduling.volcano.sh/v1beta1/queues/{name}` — returns 404 to trigger creation
- `HandleCreateQueue` — `POST /apis/scheduling.volcano.sh/v1beta1/queues` — stores queue in memory
- `HandleUpdateQueueStatus` — `PUT /apis/scheduling.volcano.sh/v1beta1/queues/{name}/status` — updates queue status

**PodGroup Handlers** (Volcano auto-creates one per pod):
- `HandleListPodGroups` — returns empty list (Volcano creates them internally)
- `HandleCreatePodGroup`, `HandleUpdatePodGroup`, `HandlePatchPodGroup` — accept and discard (we don't need to track them)

**Topology/Nodeinfo Stubs** (always-registered informers):
- `HandleListHyperNodes` — empty list + idle watch
- `HandleListNumaTopologies` — empty list + idle watch

**Kubernetes Stubs** (watched by Volcano but not kube-scheduler):
- `HandleListPriorityClasses` — empty list + idle watch
- `HandleListResourceQuotas` — empty list + idle watch
- `HandleCreateEvent`, `HandlePatchEvent` — accept and discard

#### 3. Route Registration (`main.go`)

Wired up all new handlers inside the existing `!testMode` block:
- API discovery routes (`/api`, `/api/v1`, `/apis`, `/apis/<group>/<version>`)
- Volcano CRD routes (queues, podgroups, hypernodes, numatopologies)
- Additional Kubernetes routes (priorityclasses, resourcequotas, events)

#### 4. Volcano Scheduler Config (`volcano-scheduler/`)

Three files mirroring the `second-scheduler/` pattern:

**`kubeconfig.yaml`** — identical to `second-scheduler/kubeconfig.yaml`:
```yaml
server: http://localhost:8080
insecure-skip-tls-verify: true
```

**`volcano-scheduler.conf`** — Volcano's actions/tiers config format:
```yaml
actions: "enqueue, allocate, backfill"
tiers:
- plugins:
  - name: priority
  - name: gang
- plugins:
  - name: predicates
  - name: nodeorder
    arguments:
      mostrequested.weight: 1  # bin-packing
      leastrequested.weight: 0
```

**`docker-compose.yml`** — runs `volcanosh/vc-scheduler:v1.10.0`:
```yaml
command:
  - vc-scheduler
  - --kubeconfig=/etc/volcano/kubeconfig.yaml
  - --scheduler-conf=/etc/volcano/volcano-scheduler.conf
  - --scheduler-name=volcano
  - --leader-elect=false
  - --enable-metrics=false  # avoids port conflict with adapter
  - --v=2
```

### How to Run

**Terminal 1 — Start the adapter:**
```bash
cd cloudsim-experimental/k8s-cloudsim-adapter
go run main.go --scheduler=volcano
```

**Terminal 2 — Start Volcano:**
```bash
cd cloudsim-experimental/volcano-scheduler
docker-compose up
```

Watch the logs. You should see:
1. Adapter starts on `:8080`
2. Volcano connects, calls API discovery endpoints
3. Volcano creates `root` and `default` queues (adapter logs: "Volcano queue created: root", "Volcano queue created: default")
4. Volcano starts its scheduling loop (logs: "Start scheduling ...")

**Terminal 3 — Run a COUBES benchmark:**
```bash
cd cloudsim-experimental
mvn exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test"
```

The adapter will submit pods with `spec.schedulerName: volcano`. Volcano will schedule them and bind them via the standard `/binding` endpoint (same as kube-scheduler).

### Verification

**Build:** `go build ./...` — passes cleanly  
**Tests:** `go test ./...` — all new packages pass (fakeapi, store, scheduler, main)  
**Pre-existing failure:** `communicator/conversion_test.go:205` — struct comparison bug, unrelated to our changes

### Switching Between Schedulers

| Scheduler | Adapter Flag | Scheduler Startup |
|---|---|---|
| kube-scheduler (spreading) | `--scheduler=default-scheduler` | `cd second-scheduler && docker-compose up` |
| kube-scheduler (bin-packing) | `--scheduler=my-scheduler` | `cd second-scheduler && docker-compose up` |
| Volcano (bin-packing) | `--scheduler=volcano` | `cd volcano-scheduler && docker-compose up` |

To switch Volcano to spreading, edit `volcano-scheduler/volcano-scheduler.conf`:
```yaml
mostrequested.weight: 0
leastrequested.weight: 1
```

### What's Different from kube-scheduler

| Aspect | kube-scheduler | Volcano |
|---|---|---|
| Config format | `KubeSchedulerConfiguration` | Volcano actions/tiers YAML |
| Config flag | `--config` | `--scheduler-conf` |
| Pod grouping | None | PodGroup CRD (auto-created per pod) |
| Queue concept | None | Queue CRD (created on startup) |
| Extra API groups | None | `scheduling.volcano.sh`, `topology.volcano.sh`, `nodeinfo.volcano.sh` |
| Binding API | `POST .../binding` | `POST .../binding` (same ✓) |
| Metrics port | 10259 | 8080 (conflicts with adapter — disabled with `--enable-metrics=false`) |

### Known Limitations

1. **PodGroups are tracked and deleted on reset** — PodGroups are stored in the adapter's
   in-memory store. On `/reset`, they are deleted with DELETED watch events so Volcano's
   informer cache stays in sync between test runs.

2. **Queue status updates are accepted and stored** — Volcano updates queue status after
   each scheduling cycle. We store the updates and broadcast MODIFIED watch events.

3. **HyperNode/NumaTopology are stubs** — These are Volcano's network-topology-aware
   scheduling features. We return empty lists because COUBES doesn't model network topology.

4. **No cluster-autoscaler support** — Same limitation as kube-scheduler in COUBES.

5. **Gang pods that Volcano holds (schedulable but gang not ready) cause a 5-second stall
   per round** — Volcano doesn't report these pods as unschedulable or bind them. The adapter
   waits 5 seconds of no progress before returning the partial result. For tests with large
   gangs competing for limited resources, this adds up across many rescheduling rounds.

### Current Status (2026-05-05)

The integration is complete with three Volcano-differentiating features exercised:
- **Queue Resource Management** (proportion plugin) — multi-queue with weighted resource sharing
- **Gang Scheduling** (gang plugin) — all-or-nothing PodGroup scheduling
- **Bin-packing** (nodeorder plugin) — same as before

All features produce measurably different scheduling decisions from kube-scheduler.
See `volcano-future-steering.md` for detailed implementation notes and benchmark results.
