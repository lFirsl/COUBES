# COUBES Architecture Design

## Overview

COUBES (Container Orchestration Universal Benchmark for Evaluating Schedulers) is a framework that integrates CloudSim 7G discrete-event simulation with real Kubernetes schedulers. The system delegates scheduling decisions from CloudSim to a live kube-scheduler instance, enabling reproducible benchmarking of different scheduling policies (spreading vs bin-packing) with metrics like energy consumption, consolidation ratio, and throughput.

**Key Innovation:** The adapter implements a fake Kubernetes API server that kube-scheduler connects to directly. No real Kubernetes cluster, etcd, or KWOK is required.

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                         CloudSim Simulation                          │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Live_Kubernetes_Broker_Ex                                     │ │
│  │  - Manages VMs (nodes) and Cloudlets (tasks)                  │ │
│  │  - Delegates scheduling to external K8s scheduler              │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │ HTTP                                  │
│                              ▼                                       │
└──────────────────────────────┼───────────────────────────────────────┘
                               │
                               │ POST /nodes
                               │ POST /schedule-pods
                               │ POST /pods/update-state
                               │ DELETE /reset
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                    Go Adapter (Port 8080)                            │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  Communicator (communicator/)                                  │ │
│  │  - Handles CloudSim HTTP requests                             │ │
│  │  - Converts CloudSim VMs/Cloudlets to K8s Nodes/Pods          │ │
│  │  - Coordinates scheduling rounds                               │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                       │
│                              ▼                                       │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  InMemoryStore (store/)                                        │ │
│  │  - Thread-safe in-memory storage for nodes and pods           │ │
│  │  - Implements watch/list semantics with broadcast channels    │ │
│  │  - Emits ADDED/MODIFIED/DELETED events                        │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │                                       │
│         ┌────────────────────┴────────────────────┐                 │
│         ▼                                         ▼                 │
│  ┌─────────────────┐                    ┌──────────────────────┐   │
│  │ SchedulingRound │                    │ TestModeScheduler    │   │
│  │ (scheduler/)    │                    │ (scheduler/)         │   │
│  │ - Sync barrier  │                    │ - Round-robin        │   │
│  │ - Collects      │                    │ - Standalone mode    │   │
│  │   bindings      │                    │ - No external deps   │   │
│  └─────────────────┘                    └──────────────────────┘   │
│         │                                                            │
│         │ (Full Mode Only)                                          │
│         ▼                                                            │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │  FakeAPIHandler (fakeapi/)                                     │ │
│  │  - Implements Kubernetes API endpoints                         │ │
│  │  - GET /api/v1/nodes, /api/v1/pods (list/watch)              │ │
│  │  - POST /api/v1/.../binding (protobuf)                        │ │
│  │  - PATCH /api/v1/.../status                                   │ │
│  └────────────────────────────────────────────────────────────────┘ │
│                              │ HTTP                                  │
└──────────────────────────────┼───────────────────────────────────────┘
                               │
                               │ GET /api/v1/pods?watch=true
                               │ POST /api/v1/.../binding
                               │
┌──────────────────────────────▼───────────────────────────────────────┐
│                    kube-scheduler (Docker)                           │
│  - Connects to adapter as if it were a real API server              │
│  - Two profiles: default-scheduler (spread), my-scheduler (pack)    │
│  - Watches for unscheduled pods, sends binding decisions            │
└──────────────────────────────────────────────────────────────────────┘
```

---

## Operational Modes

### Test Mode (`--test-mode`)

**Purpose:** Rapid development and testing without external dependencies.

**Flow:**
1. CloudSim sends VMs → Adapter stores them in InMemoryStore
2. CloudSim sends Cloudlets → Adapter creates pods in InMemoryStore
3. Adapter calls `TestModeScheduler.Schedule()` synchronously
4. Round-robin assignment: pod `i` → `sortedNodes[i % M]`
5. Returns assignments immediately to CloudSim

**Characteristics:**
- No kube-scheduler required
- Deterministic scheduling (lexicographic node order)
- Fake API routes not registered
- Instant response (no polling)

### Full Mode (default)

**Purpose:** Benchmark real kube-scheduler with different policies.

**Flow:**
1. CloudSim sends VMs → Adapter stores them in InMemoryStore
2. Adapter exposes nodes via fake API (`GET /api/v1/nodes`)
3. kube-scheduler watches nodes (establishes watch stream)
4. CloudSim sends Cloudlets → Adapter creates pods in InMemoryStore
5. Adapter exposes pods via fake API (`GET /api/v1/pods`)
6. kube-scheduler watches pods, sees unscheduled pods
7. kube-scheduler sends binding requests (`POST /api/v1/.../binding`)
8. Adapter parses protobuf binding, records decision in SchedulingRound
9. When all pods scheduled, returns assignments to CloudSim

**Characteristics:**
- Real kube-scheduler integration
- Supports multiple scheduler profiles (spreading/bin-packing)
- Protobuf binding protocol
- Asynchronous with timeout (60s default)

---

## Component Details

### CloudSim Side (Java)

#### `Live_Kubernetes_Broker_Ex`
**Location:** `src/main/java/org/example/kubernetes_broker/Live_Kubernetes_Broker_Ex.java`

**Purpose:** Custom CloudSim broker that delegates cloudlet-to-VM scheduling to Kubernetes.

**Key Methods:**
- `processVmCreateAck()` → calls `sendAllActiveNodesToControlPlane()` → `POST /nodes`
- `submitCloudlets()` → serializes cloudlets → `POST /schedule-pods` → receives assignments → `cloudSimAllocation()`
- `processCloudletReturn()` → `POST /pods/update-state` → watches for rescheduling

**Lifecycle:**
1. VMs created in CloudSim datacenter
2. Broker receives VM creation acknowledgments
3. Sends all VMs to adapter as nodes
4. Submits cloudlets for scheduling
5. Receives pod-to-node assignments
6. Binds cloudlets to VMs in CloudSim
7. Simulation runs with those assignments
8. When cloudlets complete, notifies adapter for rescheduling

#### `PowerDatacenterCustom`
**Location:** `src/main/java/org/example/kubernetes_broker/PowerDatacenterCustom.java`

**Purpose:** Extended datacenter with energy tracking and consolidation metrics.

**Features:**
- Time-weighted consolidation ratio (cloudlets/active VMs)
- Deferred VM destruction (VMs persist until cloudlet queue empty)
- `disableDeallocation` flag for fragmentation tests
- Energy consumption via linear interpolation

#### `PowerVmCustom`
**Location:** `src/main/java/org/example/kubernetes_broker/PowerVmCustom.java`

**Purpose:** Extended VM with host pinning and dynamic MIPS calculation.

**Features:**
- `preferredHostId` for VM-to-host affinity
- Dynamic MIPS based on active cloudlets (not static allocation)

#### Test Scenarios
**Location:** `src/main/java/org/example/testSuite/`

- `Fragmentation_Test.java` - Bin-packing under mixed workloads
- `Performance_vs_Efficiency_Test.java` - Energy vs throughput tradeoff
- `Undercrowding_Test.java` - Sparse workload idle energy

---

### Adapter Side (Go)

#### `main.go`
**Location:** `k8s-cloudsim-adapter/main.go`

**Purpose:** Entry point, HTTP server setup, route registration.

**Key Logic:**
```go
// Parse flags
--test-mode      // Enable standalone round-robin mode
--scheduler      // Target scheduler profile name (default-scheduler, my-scheduler)
--kubeconfig     // Ignored (fake API server, no real cluster)

// Initialize components
store := NewInMemoryStore()
round := NewSchedulingRound(60s)
comm := NewCommunicator(store, round, schedulerName, testMode)
fakeAPI := NewFakeAPIHandler(store)

// Register routes
// Simulation-facing (always registered):
POST   /nodes
POST   /schedule-pods
POST   /pods/update-state
DELETE /reset
GET    /pods/{id}/status

// Kubernetes API (only in full mode):
GET    /api/v1/nodes
GET    /api/v1/pods
POST   /api/v1/.../binding
PATCH  /api/v1/.../status
... (stub endpoints for services, PVs, etc.)
```

#### `communicator/communicator.go`
**Location:** `k8s-cloudsim-adapter/communicator/communicator.go`

**Purpose:** Bridge between CloudSim HTTP requests and internal scheduling logic.

**Key Handlers:**

**`HandleNodes()`** - `POST /nodes`
- Receives CloudSim VM list
- Performs diff: adds missing nodes, removes stale nodes
- Stores nodes in InMemoryStore
- InMemoryStore emits ADDED/DELETED watch events

**`HandleSchedulePods()`** - `POST /schedule-pods`
- Receives CloudSim cloudlet list
- Converts to Kubernetes pod objects
- Stores pods in InMemoryStore (with `nodeName=""` = unscheduled)
- **Test Mode:** Calls `TestModeScheduler.Schedule()` synchronously
- **Full Mode:** Calls `round.Begin(N)`, waits for kube-scheduler bindings
- Returns `BatchDecision` with pod-to-node assignments

**`HandleUpdateState()`** - `POST /pods/update-state`
- Receives completed cloudlet ID
- Deletes pod from InMemoryStore
- Collects pending pods (pods with no `nodeName`)
- **Test Mode:** If pending pods exist, reschedules them via `TestModeScheduler`
- **Full Mode:** Waits for kube-scheduler to reschedule pending pods
- Returns newly scheduled pods

**`HandleReset()`** - `DELETE /reset`
- Calls `store.DeleteAll()` (emits DELETED events for all resources)
- Calls `store.Reset()` (clears state, resets resource version)
- Calls `round.Reset()` (cancels active scheduling round)

**`HandlePodStatus()`** - `GET /pods/{id}/status`
- Returns in-memory pod status by CloudSim ID

#### `store/store.go`
**Location:** `k8s-cloudsim-adapter/store/store.go`

**Purpose:** Thread-safe in-memory storage with Kubernetes watch semantics.

**Data Structures:**
```go
type InMemoryStore struct {
    nodes           map[string]*corev1.Node
    pods            map[string]*corev1.Pod
    resourceVersion int64
    
    nodeEventCh     chan metav1.WatchEvent
    podEventCh      chan metav1.WatchEvent
    nodeBroadcaster *BroadcastServer
    podBroadcaster  *BroadcastServer
}
```

**Key Operations:**
- `CreateNode/Pod()` - Adds resource, increments resourceVersion, emits ADDED event
- `UpdateNode/Pod()` - Modifies resource, increments resourceVersion, emits MODIFIED event
- `DeleteNode/Pod()` - Removes resource, increments resourceVersion, emits DELETED event
- `SubscribeNodes/Pods()` - Returns channel for watch stream
- `Reset()` - Clears all state, recreates broadcast channels

**Watch Semantics:**
- kube-scheduler calls `GET /api/v1/pods?watch=true`
- FakeAPIHandler subscribes to `store.SubscribePods()`
- Store sends ADDED/MODIFIED/DELETED events as they occur
- kube-scheduler's internal cache stays in sync

#### `store/broadcast.go`
**Location:** `k8s-cloudsim-adapter/store/broadcast.go`

**Purpose:** Fan-out watch events to multiple subscribers (kube-scheduler watch streams).

**Pattern:**
- Single source channel (e.g., `podEventCh`)
- Multiple subscriber channels (one per watch request)
- Goroutine reads from source, writes to all subscribers
- Handles subscriber cancellation without blocking

#### `scheduler/scheduler.go`
**Location:** `k8s-cloudsim-adapter/scheduler/scheduler.go`

**Purpose:** Synchronization barrier for full mode scheduling rounds.

**Data Structures:**
```go
type SchedulingRound struct {
    pending     int                // countdown
    assignments []PodAssignment    // accumulated bindings
    failures    []PodFailure       // unschedulable pods
    decisions   chan BatchDecision // unblocked when pending == 0
    timeout     time.Duration
    active      bool
}
```

**Flow:**
1. `Begin(N)` - Initialize round expecting N decisions
2. `RecordBinding(podName, nodeName)` - Called by FakeAPIHandler when binding received
3. Decrement `pending`, append to `assignments`
4. When `pending == 0`, send `BatchDecision` to `decisions` channel
5. `Wait()` - Blocks until `BatchDecision` ready or timeout

**Concurrency:**
- Multiple binding requests can arrive concurrently
- Mutex protects `pending`, `assignments`, `failures`
- Channel `decisions` unblocks exactly once per round

#### `scheduler/test_mode_scheduler.go`
**Location:** `k8s-cloudsim-adapter/scheduler/test_mode_scheduler.go`

**Purpose:** Standalone round-robin scheduler for test mode.

**Algorithm:**
```
Sort nodes lexicographically by name
Track freePes[] per node
For each pod (round-robin starting index):
  Try nodes starting from rrIndex, wrapping around
  First node with freePes >= pod.Pes gets the pod
  If no node fits → unschedulable
```

**Properties:**
- Deterministic (same input → same output)
- Resource-aware (tracks free PEs, returns unschedulable pods)
- Balanced (round-robin across nodes with capacity)
- No external dependencies
- Tested with property-based tests (rapid)

#### `fakeapi/handlers.go`
**Location:** `k8s-cloudsim-adapter/fakeapi/handlers.go`

**Purpose:** Implements Kubernetes API server endpoints for kube-scheduler.

**Key Handlers:**

**`HandleListNodes()`** - `GET /api/v1/nodes`
- Query param `watch=true` → establish watch stream
- Query param `watch=false` → return node list snapshot
- Watch: subscribes to `store.SubscribeNodes()`, streams events as JSON

**`HandleListPods()`** - `GET /api/v1/pods`
- Query param `watch=true` → establish watch stream
- Query param `watch=false` → return pod list snapshot
- Watch: subscribes to `store.SubscribePods()`, streams events as JSON

**`HandleBinding()`** - `POST /api/v1/namespaces/default/pods/{name}/binding`
- Receives protobuf binding request from kube-scheduler
- Parses body to extract target node name
- Updates pod in store: sets `pod.Spec.NodeName = nodeName`
- Calls `round.RecordBinding(podName, nodeName)`
- Returns HTTP 201 Created

**`HandlePodStatusPatch()`** - `PATCH /api/v1/namespaces/default/pods/{name}/status`
- Receives JSON patch for pod status updates
- Applies patch to pod in store
- Used by kube-scheduler to mark pods as unschedulable

**Stub Handlers:**
- `HandleListServices()`, `HandleListPersistentVolumes()`, etc.
- Return empty lists (kube-scheduler queries these but doesn't need them)
- Prevents 404 errors in scheduler logs

**Protobuf Binding Parsing:**
```go
// kube-scheduler sends protobuf-encoded binding
// Format: <varint length><protobuf message>
// Message contains: metadata.name, target.name

// Simple string parsing approach:
body := readAll(r.Body)
nodeNamePattern := `"name":"(csnode-\d+)"`
matches := regexp.FindStringSubmatch(string(body), nodeNamePattern)
nodeName := matches[1]
```

---

## Data Flow: Scheduling a Batch of Cloudlets

### Full Mode Flow

```
1. CloudSim: Create 10 VMs
   └─> Broker.processVmCreateAck()
       └─> POST /nodes with VM list
           └─> Communicator.HandleNodes()
               └─> store.CreateNode() for each VM
                   └─> Emits ADDED events
                       └─> kube-scheduler watch stream receives nodes

2. CloudSim: Submit 20 cloudlets
   └─> Broker.submitCloudlets()
       └─> POST /schedule-pods with cloudlet list
           └─> Communicator.HandleSchedulePods()
               ├─> store.CreatePod() for each cloudlet (nodeName="")
               │   └─> Emits ADDED events
               │       └─> kube-scheduler watch stream receives unscheduled pods
               ├─> round.Begin(20)
               └─> round.Wait() [BLOCKS]

3. kube-scheduler: Sees 20 unscheduled pods
   └─> For each pod:
       ├─> Run filters (NodeResourcesFit, etc.)
       ├─> Run score plugins (LeastAllocated or MostAllocated)
       ├─> Select best node
       └─> POST /api/v1/.../binding with protobuf body
           └─> FakeAPIHandler.HandleBinding()
               ├─> Parse protobuf → extract nodeName
               ├─> store.UpdatePod(pod.Spec.NodeName = nodeName)
               │   └─> Emits MODIFIED event
               └─> round.RecordBinding(podName, nodeName)
                   └─> Decrement pending counter
                       └─> When pending == 0:
                           └─> Send BatchDecision to decisions channel

4. Adapter: round.Wait() unblocks
   └─> Returns BatchDecision{Scheduled: [{podId:1, nodeId:3}, ...]}
       └─> HTTP 200 response to CloudSim

5. CloudSim: Receives assignments
   └─> Broker.processScheduledPodsResponse()
       └─> Broker.cloudSimAllocation()
           └─> For each assignment:
               └─> sendNow(CLOUDLET_SUBMIT, cloudlet, vm)
                   └─> CloudSim binds cloudlet to VM
                       └─> Simulation executes with those assignments

6. CloudSim: Cloudlet completes
   └─> Broker.processCloudletReturn()
       └─> POST /pods/update-state with completed cloudlet ID
           └─> Communicator.HandleUpdateState()
               ├─> store.DeletePod(completedPodName)
               │   └─> Emits DELETED event
               ├─> Collect pending pods (if any)
               ├─> If pending pods exist:
               │   ├─> round.Begin(pendingCount)
               │   └─> round.Wait() [BLOCKS for rescheduling]
               └─> Returns newly scheduled pods (if any)
```

### Test Mode Flow

```
1. CloudSim: Create 10 VMs
   └─> POST /nodes
       └─> store.CreateNode() for each VM

2. CloudSim: Submit 20 cloudlets
   └─> POST /schedule-pods
       └─> Communicator.HandleSchedulePods()
           ├─> store.CreatePod() for each cloudlet
           ├─> testSched.Schedule(pods, nodes)
           │   └─> Round-robin: pod[i] → sortedNodes[i % 10]
           └─> Returns BatchDecision immediately

3. CloudSim: Receives assignments
   └─> Binds cloudlets to VMs
       └─> Simulation executes

4. CloudSim: Cloudlet completes
   └─> POST /pods/update-state
       └─> store.DeletePod()
       └─> If pending pods:
           └─> testSched.Schedule(pendingPods, nodes)
               └─> Returns new assignments
```

---

## Key Design Decisions

### Why Fake API Server Instead of KWOK?

**KWOK Approach (Old):**
- Required Docker Desktop + KWOK cluster running
- Full Kubernetes control plane (etcd, kube-apiserver, kube-controller-manager)
- Adapter was a client, not a server
- Polling-based: adapter repeatedly queries pod status
- Heavyweight: ~500MB memory, slow startup

**Fake API Server Approach (Current):**
- No external dependencies (just Go binary + optional Docker for scheduler)
- Adapter implements minimal API surface kube-scheduler needs
- Event-driven: watch streams push updates immediately
- Lightweight: ~50MB memory, instant startup
- Inspired by k8s-in-the-loop project

### Why Two Modes (Test vs Full)?

**Test Mode:**
- Rapid iteration during development
- Deterministic results for debugging
- No Docker/scheduler setup required
- Useful for CI/CD pipelines

**Full Mode:**
- Benchmark real scheduler behavior
- Compare different scheduling policies
- Validate against production scheduler logic
- Research-grade results

### Why Protobuf Bindings?

kube-scheduler sends binding requests in protobuf format (not JSON). The adapter parses protobuf bodies using simple string pattern matching to extract node names. This avoids the complexity of full protobuf deserialization while remaining robust for the limited message types we handle.

### Why SchedulingRound Synchronization?

CloudSim is single-threaded and blocks on HTTP requests. The adapter must wait until all pods are scheduled before returning. `SchedulingRound` acts as a countdown latch: it blocks `HandleSchedulePods()` until kube-scheduler has sent bindings for all N pods (or timeout expires).

### Why InMemoryStore Watch Semantics?

kube-scheduler expects Kubernetes list/watch semantics:
1. Initial LIST returns current state
2. WATCH returns a stream of ADDED/MODIFIED/DELETED events
3. Scheduler builds an internal cache from these events

InMemoryStore implements this pattern with broadcast channels, allowing multiple concurrent watch streams (e.g., one for nodes, one for pods).

---

## File Reference

### Go Adapter (`k8s-cloudsim-adapter/`)

| File | Purpose |
|------|---------|
| `main.go` | Entry point, HTTP server, route registration, flag parsing |
| `communicator/communicator.go` | CloudSim-facing HTTP handlers, VM/cloudlet conversion |
| `communicator/conversion_utils.go` | CloudSim ↔ Kubernetes type conversions |
| `communicator/k8s-simplified-structs.go` | Simplified CloudSim data structures (CsNode, CsPod) |
| `store/store.go` | Thread-safe in-memory node/pod storage with watch semantics |
| `store/broadcast.go` | Fan-out watch events to multiple subscribers |
| `scheduler/scheduler.go` | SchedulingRound synchronization barrier for full mode |
| `scheduler/test_mode_scheduler.go` | Round-robin scheduler for test mode |
| `fakeapi/handlers.go` | Kubernetes API endpoints for kube-scheduler |
| `utils/general_utils.go` | Utility functions (logging, error handling) |

### Java CloudSim (`src/main/java/org/example/`)

| File | Purpose |
|------|---------|
| `kubernetes_broker/Live_Kubernetes_Broker_Ex.java` | Main broker, delegates scheduling to adapter |
| `kubernetes_broker/PowerDatacenterCustom.java` | Datacenter with energy tracking, consolidation metrics |
| `kubernetes_broker/PowerVmCustom.java` | VM with host pinning, dynamic MIPS |
| `kubernetes_broker/PerformanceMetrics.java` | Throughput tracking (EWMA, sliding window) |
| `metrics/SimulationMetrics.java` | Metrics aggregation and printing |
| `metrics/TimeWeightedMetric.java` | Time-weighted average calculator |
| `helper/Helper.java` | VM/host factory, result printing, SLA metrics |
| `helper/Constants.java` | Simulation constants (host specs, power models) |
| `testSuite/Fragmentation_Test.java` | Bin-packing benchmark |
| `testSuite/Performance_vs_Efficiency_Test.java` | Energy vs throughput benchmark |
| `testSuite/Undercrowding_Test.java` | Sparse workload benchmark |

### Scheduler Configuration (`second-scheduler/`)

| File | Purpose |
|------|---------|
| `Dockerfile` | Builds kube-scheduler image with custom config |
| `docker-compose.yml` | Runs scheduler with `network_mode: host` |
| `my-scheduler.yaml` | KubeSchedulerConfiguration with two profiles |
| `kubeconfig.yaml` | Points scheduler to adapter on localhost:8080 |
| `README.md` | Setup instructions, profile selection, troubleshooting |

---

## Metrics Collected

| Metric | Source | Description |
|--------|--------|-------------|
| Energy (Wh) | `PowerDatacenterCustom.getPower()` | Total energy consumed by hosts |
| Consolidation Ratio | `PowerDatacenterCustom.getConsolidationAverage()` | Time-weighted cloudlets/active VMs |
| Simulated Time | `CloudSim.startSimulation()` | Simulation clock time |
| Wall-Clock Time | `SimulationMetrics` | Real execution time |
| Throughput (pods/sec) | `Live_Kubernetes_Broker_Ex` | EWMA, sliding window, overall average |
| SLA Violations | `Helper.getSlaMetrics()` | Time with allocated < requested MIPS |

---

## Testing Strategy

### Unit Tests (Go)
- `scheduler/test_mode_scheduler_test.go` - Round-robin correctness
- `store/store_test.go` - Thread safety, watch semantics
- `fakeapi/handlers_test.go` - API endpoint behavior
- `communicator_test/communicator_test.go` - Integration tests

### Property-Based Tests (Go)
Uses `pgregory.net/rapid` for generative testing:
- Property 1: Assignment completeness (all pods accounted for)
- Property 2: Round-robin formula correctness
- Property 3: Balance invariant (even distribution)
- Property 4: BindingTimestamp presence
- Property 5: Update-state rescheduling
- Property 6: Reset empties store

### Integration Tests (Java)
- `Fragmentation_Test` - 20 cloudlets, 5 VMs, 2 waves, bin-packing benchmark
- `Fragmentation_Test_Large` - 50 cloudlets, 2 VMs, rescheduling stress test
- `Fragmentation_Test_5Wave` - 5 waves of mixed cloudlets, multi-round rescheduling
- `Performance_vs_Efficiency_Test` - Energy vs throughput tradeoff (2 hosts, different power models)
- `Undercrowding_Test` - Sparse workload, idle energy waste
- `Scheduler_Scalability_Test` - Scheduling latency at 4:1 and 20:1 pod-to-node ratios
- `Scheduler_Latency_Test` - Per-pod scheduling latency under increasing load

---

## Running COUBES

### Using run_test.sh (Preferred)

`run_test.sh` handles everything: killing stale processes, compiling, starting
infrastructure, hang detection, and output filtering.

```bash
# Test mode (no Docker required)
bash run_test.sh --test-mode org.example.testSuite.Fragmentation_Test_Large

# Full mode (real kube-scheduler)
bash run_test.sh org.example.testSuite.Scheduler_Scalability_Test

# Skip compilation
bash run_test.sh --test-mode --no-compile org.example.testSuite.Fragmentation_Test

# Run all tests
bash run_all_tests.sh --test-mode
bash run_all_tests.sh              # full mode
```

See `run_test.sh --help` and `run_all_tests.sh --help` for all options.

### Manual Startup (Debugging Only)

See `docs/scheduling-workflow.md` for the detailed scheduling flow and
`.kiro/steering/operational-runbook.md` for manual startup procedures.

---

## Future Extensions

### Cluster Autoscaler Integration
The fake API server architecture enables cluster-autoscaler integration:
- Autoscaler watches for unschedulable pods
- Requests new nodes via scale-up API
- Adapter notifies CloudSim to create new VMs
- CloudSim allocates VMs on hosts
- Adapter exposes new nodes to autoscaler

### Multi-Tenant Scenarios
Currently all resources in `default` namespace. Could extend to:
- Multiple namespaces with resource quotas
- Pod priority and preemption
- Node selectors and taints/tolerations

### Custom Scheduler Plugins
The fake API server supports any kube-scheduler configuration:
- Custom score plugins (e.g., carbon-aware scheduling)
- Custom filter plugins (e.g., GPU affinity)
- Scheduler extenders (webhook-based)

---

## Troubleshooting

### Adapter fails to start
- Check port 8080 is not in use: `netstat -an | grep 8080`
- Verify Go version: `go version` (requires 1.21+)

### Scheduler can't connect to adapter
- Verify adapter is running: `curl http://localhost:8080/api/v1/nodes`
- Check `kubeconfig.yaml` server URL is `http://localhost:8080`
- Ensure `docker-compose.yml` has `network_mode: "host"`

### Pods not scheduling
- Increase scheduler log verbosity: `--v=4` in `docker-compose.yml`
- Check adapter logs for binding requests
- Verify nodes have sufficient CPU/memory for pod requests

### Simulation hangs
- Check adapter logs for timeout errors
- Verify all cloudlets have valid resource requests
- Ensure `POST /schedule-pods` returns within 60s

### Test failures
- Run with verbose output: `go test -v ./...`
- Check property test corpus: `testdata/rapid/`
- Verify CloudSim 7.0.1 is installed: `mvn dependency:tree`

---

## References

- CloudSim 7G: https://github.com/Cloudslab/cloudsim
- k8s-in-the-loop: Straesser et al., EAI VALUETOOLS 2023
- KWOK: https://kwok.sigs.k8s.io/
- kube-scheduler: https://kubernetes.io/docs/concepts/scheduling-eviction/kube-scheduler/
