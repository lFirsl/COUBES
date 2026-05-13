# COUBES Architecture Design

## Overview

COUBES (Container Orchestration Universal Benchmark for Evaluating Schedulers) is a framework that integrates CloudSim 7G discrete-event simulation with real container orchestration schedulers. The system delegates scheduling decisions from CloudSim to a live scheduler instance, enabling reproducible benchmarking of different scheduling policies with metrics like energy consumption, consolidation ratio, throughput, and scheduling latency.

**Key Innovation:** The adapter implements a fake API server that schedulers connect to directly. No real cluster, etcd, or KWOK is required. Any scheduler that speaks its native protocol can be plugged in unmodified.

**Supported Schedulers:**
- **kube-scheduler v1.33** — LeastAllocated (spreading), MostAllocated (bin-packing)
- **Volcano vc-scheduler v1.10** — Proportion (queue fairness), Gang (all-or-nothing), NodeOrder (bin-packing)

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CloudSim Simulation                          │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Test Scenario (defines infra, workloads, waves, gangs, queues)│ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │ configures                            │
│                              ▼                                       │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Discrete-Event Engine (deterministic clock, drives all events)│ │
│  └──────────────┬─────────────────────────────────┬───────────────┘ │
│                 │ events                           │ events          │
│                 ▼                                  ▼                 │
│  ┌──────────────────────────┐      ┌──────────────────────────┐   │
│  │  Live_Kubernetes_Broker  │◄────►│  PowerDatacenterCustom   │   │
│  │  - Scheduling rounds     │submit│  - Hosts, VMs            │   │
│  │  - Gang holding/deadlock │─────►│  - Power models          │   │
│  │  - Queue mapping         │◄─────│  - Energy tracking       │   │
│  │  - Rescheduling loop     │ done │  - Consolidation ratio   │   │
│  └──────────────────────────┘      └──────────────────────────┘   │
│         │  ▲                                                        │
│         │  │ manages                                                │
│         ▼  │                                                        │
│  ┌──────────────────────────┐                                      │
│  │  CoubesCloudlets         │                                      │
│  │  - length (MI), PEs      │                                      │
│  │  - gangId, classType     │                                      │
│  │  - ramRequest, labels    │                                      │
│  │  - affinity/anti-affinity│                                      │
│  └──────────────────────────┘                                      │
│                                                                      │
│         │ HTTP (POST /schedule)                                      │
└─────────┼────────────────────────────────────────────────────────────┘
          │
          │ SimulationSnapshot: nodes + pods + completedPodIds
          │ Response: BatchDecision (assignments + unschedulable)
          │
┌─────────▼────────────────────────────────────────────────────────────┐
│                    Go Adapter (Port 8080)                            │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Simulation-Facing Interface (communicator/)                   │ │
│  │  - POST /nodes — sync VMs as K8s nodes                        │ │
│  │  - POST /schedule — submit snapshot, block for decisions      │ │
│  │  - DELETE /reset — clear all state between runs               │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                       │
│                              ▼                                       │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  InMemoryStore (store/)                                        │ │
│  │  - Nodes, Pods, Queues, PodGroups                             │ │
│  │  - Watch broadcast channels per resource type                 │ │
│  │  - Resource version tracking                                   │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                       │
│         ┌────────────────────┴────────────────────┐                 │
│         ▼                                         ▼                 │
│  ┌─────────────────┐                    ┌──────────────────────┐   │
│  │ SchedulingRound │                    │ TestModeScheduler    │   │
│  │ (scheduler/)    │                    │ (scheduler/)         │   │
│  │ - Sync barrier  │                    │ - Round-robin        │   │
│  │ - Partial result│                    │ - Resource-aware     │   │
│  │   on timeout    │                    │ - No external deps   │   │
│  │ - Stall exit    │                    │                      │   │
│  └─────────────────┘                    └──────────────────────┘   │
│         │                                                            │
│         │ (Full Mode Only)                                          │
│         ▼                                                            │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Scheduler-Facing Interface (fakeapi/)                         │ │
│  │  - K8s core: nodes, pods (list/watch), binding (protobuf)     │ │
│  │  - Volcano CRDs: queues, podgroups, hypernodes, numatopology  │ │
│  │  - API discovery: /api, /apis, /apis/<group>/<version>        │ │
│  │  - Status: PUT/PATCH pod status (unschedulable detection)     │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                       │
└──────────────────────────────┼───────────────────────────────────────┘
                               │
                               │ Native scheduler protocol
                               │ (K8s API: watch, binding, status)
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                    CO Scheduler (Docker, unmodified)                 │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  kube-scheduler v1.33                                        │   │
│  │  - LeastAllocated (spreading) / MostAllocated (bin-packing) │   │
│  └─────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Volcano vc-scheduler v1.10                                  │   │
│  │  - proportion (queue fairness), gang (all-or-nothing)       │   │
│  │  - nodeorder (bin-packing), predicates, backfill            │   │
│  └─────────────────────────────────────────────────────────────┘   │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Any scheduler speaking the same protocol (extensible)       │   │
│  └─────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Operational Modes

### Test Mode (`--test-mode`)

**Purpose:** Rapid development and testing without external dependencies.

**Flow:**
1. CloudSim sends VMs → Adapter stores them in InMemoryStore
2. CloudSim sends SimulationSnapshot → Adapter creates pods in InMemoryStore
3. Adapter calls `TestModeScheduler.Schedule()` synchronously
4. Resource-aware round-robin: first node with enough free PEs gets the pod
5. Returns assignments immediately to CloudSim

**Characteristics:**
- No scheduler container required
- Deterministic scheduling (lexicographic node order)
- Fake API routes not registered
- Instant response (no polling)
- Resource-aware (tracks free PEs, returns unschedulable pods)

### Full Mode (default)

**Purpose:** Benchmark real schedulers with different policies.

**Flow:**
1. CloudSim sends VMs → Adapter stores as nodes, emits ADDED watch events
2. Scheduler watches nodes (establishes watch stream)
3. CloudSim sends SimulationSnapshot → Adapter creates pods (with gang/queue metadata)
4. Scheduler watches pods, sees unscheduled pods
5. Scheduler sends binding requests (pod → node assignment)
6. Adapter records binding in SchedulingRound
7. When all pods resolved (or timeout), returns BatchDecision to CloudSim
8. On timeout, unresolved pods returned as unschedulable (partial result)

**Characteristics:**
- Real scheduler integration (kube-scheduler or Volcano)
- Supports multiple scheduling paradigms
- Protobuf binding protocol (kube-scheduler) or PUT status (Volcano)
- Asynchronous with 30s timeout + 5s stall exit

---

## Component Details

### CloudSim Side (Java)

#### `Live_Kubernetes_Broker_Ex`
**Location:** `src/main/java/org/example/kubernetes_broker/Live_Kubernetes_Broker_Ex.java`

**Purpose:** Custom CloudSim broker that delegates cloudlet-to-VM scheduling to the middleware.

**Key Methods:**
- `processVmCreateAck()` → `sendAllActiveNodesToControlPlane()` → `POST /nodes`
- `submitCloudlets()` → builds SimulationSnapshot (nodes + pods + completedPodIds) → `POST /schedule` → `processBatchDecision()` → `cloudSimAllocation()`
- `processCloudletReturn()` → adds to `completedSinceLastRound`, schedules `RESCHEDULE_PENDING` event
- `processOtherEvent(RESCHEDULE_PENDING)` → calls `submitCloudlets()` again (rescheduling loop)

**Gang Scheduling:**
- `gangWaitingRoom` — holds scheduled gang members until all members are placed
- `gangExpectedSizes` — tracks how many members each gang has
- When `gangWaitingRoom[gangId].size() == expected` → all members submitted to CloudSim simultaneously
- Deadlock detection: if nothing is running and gang can't complete → mark all members FAILED

**Queue Mapping:**
- `QUEUE_NAMES` map: classType 1 → "high-priority", classType 2 → "batch"
- Included in pod JSON sent to adapter → adapter creates PodGroup with matching queue

**Rescheduling Loop:**
- On cloudlet completion, `RESCHEDULE_PENDING` event fires after 1s (batches completions)
- `submitCloudlets()` re-sends pending pods with `completedPodIds` so adapter frees capacity
- Permanent failure detection: same pods unschedulable twice with nothing running → FAILED

#### `CoubesCloudlet`
**Location:** `src/main/java/org/example/kubernetes_broker/CoubesCloudlet.java`

**Purpose:** Extended Cloudlet with scheduling metadata.

**Fields:**
- `gangId` (String, nullable) — groups cloudlets for all-or-nothing scheduling
- `classType` (int, inherited) — maps to queue name (0=default, 1=high-priority, 2=batch)
- `ramRequest` (int, MB) — memory request for capacity-based scheduling
- `labels` (Map<String,String>) — arbitrary key-value labels
- `affinityGroup` / `antiAffinityGroup` (String) — co-location / separation constraints
- `hardAffinity` / `hardAntiAffinity` (boolean) — hard vs soft constraint

#### `PowerDatacenterCustom`
**Location:** `src/main/java/org/example/kubernetes_broker/PowerDatacenterCustom.java`

**Purpose:** Extended datacenter with energy tracking and consolidation metrics.

**Features:**
- Time-weighted consolidation ratio (cloudlets/active VMs)
- Deferred VM destruction (VMs persist until cloudlet queue empty)
- `disableDeallocation` flag for fragmentation tests
- Energy consumption via linear interpolation at each scheduling interval
- Safety net for stale-MIPS event chain death (idle VMs receiving new cloudlets)
- Log levels: QUIET / NORMAL / VERBOSE

#### `PowerVmCustom`
**Location:** `src/main/java/org/example/kubernetes_broker/PowerVmCustom.java`

**Purpose:** Extended VM with host pinning, labels, and dynamic MIPS calculation.

**Features:**
- `preferredHostId` for VM-to-host affinity
- `labels` map for node selector matching
- Dynamic MIPS based on active cloudlets

#### Test Scenarios
**Location:** `src/main/java/org/example/testSuite/`

Each test defines infrastructure (hosts, VMs, power models), workloads (cloudlets with PEs, length, gang/queue), and arrival patterns (waves with delays).

| Test | What it measures |
|------|-----------------|
| `Fragmentation_Test` | Bin-packing under mixed workloads (2 waves) |
| `Fragmentation_Test_Large` | Rescheduling stress (50 cloudlets, 2 VMs) |
| `Queue_Priority_Test` | Multi-queue fairness (proportion plugin) |
| `Gang_Scheduling_Test` | All-or-nothing atomic placement |
| `Gang_Constrained_Test` | Gang too large for cluster |
| `Overload_Comparison_Test` | 71 pods, mixed gangs + queues, full comparison |
| `Queue_Starvation_Test` | Sustained overload with queue fairness |
| `Scalability_Test` | 50 nodes, 5000 pods |
| `MultiPE_Pod_Test` | Multi-PE pods on limited nodes |
| `Heterogeneous_Node_Test` | Nodes with different PE counts |
| ... | (20+ scenarios total) |

---

### Adapter Side (Go)

#### `main.go`
**Location:** `k8s-cloudsim-adapter/main.go`

**Purpose:** Entry point, HTTP server setup, route registration.

**Key Flags:**
```
--test-mode      Enable standalone round-robin mode
--scheduler      Scheduler name: least-allocated, most-allocated, volcano
--port           Listen port (default 8080)
```

**Routes (always registered):**
```
POST   /nodes          — sync VMs as K8s nodes
POST   /schedule       — submit snapshot, block for decisions
DELETE /reset          — clear all state between runs
```

**Routes (full mode only — K8s core):**
```
GET    /api/v1/nodes              — list/watch nodes
GET    /api/v1/pods               — list/watch pods
POST   /api/v1/.../binding        — binding (protobuf)
PATCH  /api/v1/.../pods/status    — unschedulable status (kube-scheduler)
PUT    /api/v1/.../pods/status    — unschedulable status (Volcano)
```

**Routes (full mode only — Volcano CRDs):**
```
GET/POST /apis/scheduling.volcano.sh/v1beta1/queues          — queue CRUD + watch
GET/POST /apis/scheduling.volcano.sh/v1beta1/.../podgroups   — podgroup CRUD + watch
PUT      /apis/scheduling.volcano.sh/v1beta1/.../podgroups   — podgroup status update
GET      /apis/topology.volcano.sh/v1alpha1/hypernodes       — stub (empty list)
GET      /apis/nodeinfo.volcano.sh/v1alpha1/numatopologies   — stub (empty list)
```

**Routes (full mode only — API discovery):**
```
GET    /api                                    — core API versions
GET    /api/v1                                 — core v1 resources
GET    /apis                                   — all API groups
GET    /apis/scheduling.volcano.sh/v1beta1     — Volcano scheduling resources
GET    /apis/topology.volcano.sh/v1alpha1      — Volcano topology resources
GET    /apis/nodeinfo.volcano.sh/v1alpha1      — Volcano nodeinfo resources
```

#### `communicator/communicator.go`
**Location:** `k8s-cloudsim-adapter/communicator/communicator.go`

**Purpose:** Bridge between CloudSim HTTP requests and internal scheduling logic.

**Key Handlers:**

**`HandleSchedule()`** - `POST /schedule`
- Receives SimulationSnapshot (nodes, pods, completedPodIds)
- Syncs nodes (diff-based add/remove)
- Deletes completed pods from store
- Creates new pods in store (with scheduler-specific metadata)
- For Volcano: creates PodGroups (shared for gangs, individual otherwise)
- For Volcano: ensures queues exist (`ensureVolcanoQueue`)
- **Test Mode:** Calls `TestModeScheduler.Schedule()` synchronously
- **Full Mode:** Calls `round.Begin(N)`, waits for bindings or timeout
- On timeout: returns partial result (resolved pods + unschedulable remainder)
- Returns `BatchDecision` with assignments and unschedulable list

**`BuildPod()` (conversion_utils.go)**
- Converts CsPod to corev1.Pod
- Sets `spec.schedulerName` based on `--scheduler` flag
- For Volcano: adds `scheduling.k8s.io/group-name` annotation (links pod to PodGroup)
- For Volcano: sets `pod.Status.Phase = Pending` (required for task classification)
- On binding: sets `pod.Status.Phase = Running` (required for node resource accounting)

#### `store/store.go`
**Location:** `k8s-cloudsim-adapter/store/store.go`

**Purpose:** Thread-safe in-memory storage with Kubernetes watch semantics.

**Resource Types:**
- **Nodes** — `map[string]*corev1.Node` + watch broadcaster
- **Pods** — `map[string]*corev1.Pod` + watch broadcaster
- **Queues** — `map[string]map[string]interface{}` + watch broadcaster (Volcano)
- **PodGroups** — `map[string]map[string]interface{}` + watch broadcaster (Volcano)

Queues and PodGroups use raw JSON maps to avoid importing Volcano API types.

#### `scheduler/scheduler.go`
**Location:** `k8s-cloudsim-adapter/scheduler/scheduler.go`

**Purpose:** Synchronization barrier for full mode scheduling rounds.

**Key Behaviour:**
- `Begin(N)` — initialize round expecting N decisions
- `RecordBinding(podName, nodeName)` — called by FakeAPIHandler on binding
- `RecordFailure(podName, reason)` — called on unschedulable status
- `Wait()` — blocks until all resolved, timeout (30s), or stall exit (5s no progress)
- On timeout: returns partial `BatchDecision` with whatever was resolved (not empty)
- Stall exit: if no new bindings/failures for 5s after first decision → return early

#### `fakeapi/volcano_handlers.go`
**Location:** `k8s-cloudsim-adapter/fakeapi/volcano_handlers.go`

**Purpose:** Volcano-specific HTTP handlers (API discovery, queues, podgroups, stubs).

**Critical Behaviours:**
- Queue `GET /queues/{name}` returns proper K8s 404 Status object (not plain HTTP 404)
- Queue watch replays existing queues as ADDED events on connect
- PodGroup `PUT` stores update and broadcasts MODIFIED event (required for enqueue→inqueue transition)
- API discovery must list all Volcano groups or client panics

---

## Data Flow: Scheduling a Batch (Full Mode)

```
1. Test Scenario configures CloudSim (hosts, VMs, cloudlets, waves)
   └─> CloudSim.startSimulation()
       └─> Discrete-Event Engine fires VM creation events

2. Broker receives VM creation acknowledgments
   └─> POST /nodes (sync all VMs as K8s nodes)
       └─> store.CreateNode() → ADDED watch events → scheduler caches nodes

3. Broker.submitCloudlets() builds SimulationSnapshot
   └─> POST /schedule { nodes: [...], pods: [...], completedPodIds: [...] }
       └─> Adapter:
           ├─> Deletes completed pods from store (DELETED events)
           ├─> Creates new pods in store (ADDED events)
           ├─> [Volcano] Creates PodGroups, ensures queues exist
           ├─> round.Begin(N)
           └─> round.Wait() [BLOCKS]

4. Scheduler sees unscheduled pods via watch stream
   └─> Evaluates filters + scoring
   └─> Sends binding: POST /binding (kube-scheduler) or same (Volcano)
       └─> FakeAPIHandler:
           ├─> Sets pod.Spec.NodeName, pod.Status.Phase = Running
           ├─> store.UpdatePod() → MODIFIED event
           └─> round.RecordBinding(podName, nodeName)

5. round.Wait() unblocks (all resolved, timeout, or stall)
   └─> Returns BatchDecision to CloudSim

6. Broker.processBatchDecision():
   ├─> Scheduled pods: assign cloudlet.guestId = vmId
   │   ├─> [Gang] Hold in gangWaitingRoom until all members placed
   │   └─> [Non-gang] Add to cloudletsReadyForCloudsim
   ├─> Unschedulable pods: keep in cloudletsSubmittedToMiddle (retry later)
   └─> cloudSimAllocation() → sendNow(CLOUDLET_SUBMIT) to datacenter

7. Datacenter executes cloudlets (discrete-event simulation)
   └─> On completion: fires CLOUDLET_RETURN event to broker

8. Broker.processCloudletReturn():
   ├─> Adds cloudletId to completedSinceLastRound
   └─> Schedules RESCHEDULE_PENDING event (+1s)
       └─> submitCloudlets() again (loop back to step 3)

9. When all cloudlets complete: simulation terminates naturally
   └─> Metrics Engine collects results from datacenter
```

---

## Key Design Decisions

### Why Fake API Server Instead of KWOK?

| Aspect | KWOK (Old) | Fake API Server (Current) |
|--------|-----------|--------------------------|
| Dependencies | Docker + etcd + kube-apiserver + kube-controller-manager | Go binary + optional Docker for scheduler |
| Memory | ~500MB | ~50MB |
| Startup | Slow (cluster init) | Instant |
| Scheduling sync | Polling (250ms–1s loops) | Event-driven (immediate) |
| Extensibility | Limited to K8s | Any CO protocol |

### Why SimulationSnapshot Instead of Separate Endpoints?

The old design used three separate endpoints (`/nodes`, `/schedule-pods`, `/pods/update-state`). The current design uses a single `POST /schedule` that sends everything in one request:
- Nodes (for sync)
- Pods (new workloads to schedule)
- CompletedPodIds (to free capacity)

This eliminates race conditions between node sync and pod creation, and allows the adapter to atomically update state before starting a scheduling round.

### Why Partial Results on Timeout?

Volcano's `backfill` action silently skips pods it cannot schedule — it never sends a binding or unschedulable status. The adapter would wait forever. The fix: on timeout (30s) or stall (5s no progress), return whatever was resolved. Unresolved pods are marked unschedulable and retried by the broker's rescheduling loop.

### Why Gang Holding in the Broker?

Gang scheduling requires all members to be placed before any start executing. The broker holds scheduled gang members in a "waiting room" until all members are bound, then submits them all to CloudSim simultaneously. This ensures gang members start at the same simulated time regardless of when individual bindings arrive.

### Why Queue Mapping via classType?

CloudSim's `Cloudlet.classType` is a built-in integer field. Mapping it to queue names (1→"high-priority", 2→"batch") avoids modifying the Cloudlet class hierarchy while enabling multi-queue scheduling. Tests that don't set classType route everything to the default queue — behaviour is identical to before.

---

## Metrics Collected

| Metric | Source | Category | Description |
|--------|--------|----------|-------------|
| Energy (Wh) | `PowerDatacenterCustom.getPower()` | Decision (SDS) | Total energy consumed by hosts |
| Consolidation Ratio | `PowerDatacenterCustom.getConsolidationAverage()` | Decision (SDS) | Time-weighted cloudlets/active VMs |
| Simulated TTC (s) | `CloudSim.startSimulation()` return | Decision (SDS) | Simulated time to complete all cloudlets |
| HP/Batch Turnaround (s) | `SimulationMetrics.printPerQueueMetrics()` | Decision (SDS) | Per-queue average turnaround |
| Wall-Clock Time (ms) | `SimulationMetrics` | Performance (SPS) | Real execution time |
| Throughput (pods/sec) | `Live_Kubernetes_Broker_Ex` | Performance (SPS) | Effective + peak throughput |
| Scheduling Latency (ms) | `PerformanceMetrics` | Performance (SPS) | Per-pod submission → binding |

**Scoring Framework (SDS/SPS/Pareto):**
- Decision-based metrics normalised to [0,1] via theoretical bounds (from `BoundsCalculator`)
- Performance metrics expressed as ratio against baseline scheduler
- Pareto dominance analysis identifies genuine tradeoffs vs strict improvements

---

## File Reference

### Go Adapter (`k8s-cloudsim-adapter/`)

| File | Purpose |
|------|---------|
| `main.go` | Entry point, HTTP server, route registration |
| `communicator/communicator.go` | CloudSim-facing handlers, scheduling orchestration |
| `communicator/conversion_utils.go` | CloudSim ↔ K8s type conversions, BuildPod |
| `communicator/k8s-simplified-structs.go` | CsNode, CsPod structs (with Queue, GangId fields) |
| `store/store.go` | In-memory storage: nodes, pods, queues, podgroups + watch |
| `store/broadcast.go` | Fan-out watch events to multiple subscribers |
| `scheduler/scheduler.go` | SchedulingRound barrier (timeout, stall exit, partial result) |
| `scheduler/test_mode_scheduler.go` | Resource-aware round-robin for test mode |
| `fakeapi/handlers.go` | K8s core API endpoints (nodes, pods, binding, status) |
| `fakeapi/volcano_handlers.go` | Volcano CRD endpoints (queues, podgroups, discovery) |

### Java CloudSim (`src/main/java/org/example/`)

| File | Purpose |
|------|---------|
| `kubernetes_broker/Live_Kubernetes_Broker_Ex.java` | Broker: scheduling rounds, gang holding, queue mapping, rescheduling |
| `kubernetes_broker/CoubesCloudlet.java` | Extended cloudlet: gangId, ramRequest, labels, affinity |
| `kubernetes_broker/PowerDatacenterCustom.java` | Datacenter: energy, consolidation, event chain safety |
| `kubernetes_broker/PowerVmCustom.java` | VM: host pinning, labels, dynamic MIPS |
| `kubernetes_broker/CloudActionTagsEx.java` | Custom event tags (RESCHEDULE_PENDING) |
| `metrics/SimulationMetrics.java` | Metrics aggregation, per-queue metrics, JSON output |
| `metrics/PerformanceMetrics.java` | Per-pod scheduling latency tracking |
| `metrics/BoundsCalculator.java` | CP-SAT solver for theoretical min/max bounds |
| `metrics/SDS.java` | Min-max normalisation against bounds |
| `testSuite/*.java` | 20+ benchmark scenarios |

### Scheduler Configuration

| Directory | Purpose |
|-----------|---------|
| `second-scheduler/` | kube-scheduler Docker config (2 profiles: spread/pack) |
| `volcano-scheduler/` | Volcano Docker config (proportion + gang + nodeorder) |

---

## Running COUBES

### Using run_test.sh (Preferred)

```bash
# Test mode (no Docker required)
bash run_test.sh --test-mode org.example.testSuite.Fragmentation_Test

# kube-scheduler (default: least-allocated)
bash run_test.sh org.example.testSuite.Fragmentation_Test

# kube-scheduler (bin-packing)
bash run_test.sh --scheduler=most-allocated org.example.testSuite.Fragmentation_Test

# Volcano
bash run_test.sh --volcano org.example.testSuite.Queue_Priority_Test

# Run all tests
bash run_all_tests.sh [--volcano] [--no-compile] [--stop-on-fail]

# Compare schedulers (produces CSV in results/)
bash compare_schedulers.sh Fragmentation_Test
```

---

## References

- CloudSim 7G: https://github.com/Cloudslab/cloudsim
- Volcano: https://volcano.sh/
- k8s-in-the-loop: Straesser et al., EAI VALUETOOLS 2023
- kube-scheduler: https://kubernetes.io/docs/concepts/scheduling-eviction/kube-scheduler/
