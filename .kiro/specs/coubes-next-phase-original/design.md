# Design Document: COUBES Next Phase

## Overview

This document describes the technical design for the next development phase of COUBES. Three major changes are being made in parallel:

1. **Fake API Server Migration** — the Go adapter (`k8s-cloudsim-adapter/`) is transformed from a client of a KWOK cluster into an in-process fake Kubernetes API server. The kube-scheduler binary connects directly to the adapter. Docker Desktop and KWOK are eliminated as runtime dependencies.

2. **Batch-based Communication Protocol** — the CloudSim ↔ adapter protocol is restructured around a `SimulationSnapshot` / `BatchDecision` model. Each scheduling round is a single round-trip: the Broker sends a complete state snapshot (nodes + pending pods + completed pod IDs) and receives a complete set of scheduling decisions.

3. **Standalone Performance Metrics** — a new Java class (`PerformanceMetrics`) tracks per-pod scheduling latency (SubmissionTimestamp to BindingTimestamp) and overall throughput, independently of the existing `SimulationMetrics`.

The `cloudsim-7.0/` and `k8s-in-the-loop/` directories are read-only references. The Java broker's public API and existing test scenarios must remain compatible.

---

## Architecture

### Current Architecture (KWOK-based)

```
CloudSim (Java)
    │  POST /nodes, POST /schedule-pods, POST /pods/update-state
    ▼
k8s-cloudsim-adapter (Go)  ← K8s client (client-go)
    │  client-go API calls
    ▼
KWOK cluster (etcd + kube-apiserver + kube-scheduler)
    │  pod.Spec.NodeName (polled)
    ▼
adapter reads back assignments → HTTP response → CloudSim
```

**Problems:** requires Docker Desktop + KWOK running; polling adds 1–30 s latency per round; state leaks between runs if reset is skipped.

### Target Architecture (Fake API Server)

```
CloudSim (Java)
    │  POST /schedule  (SimulationSnapshot JSON)
    ▼
k8s-cloudsim-adapter (Go)  ← acts as fake kube-apiserver
    │  K8s API: GET /api/v1/nodes?watch=true
    │           GET /api/v1/pods?watch=true
    │           POST /api/v1/namespaces/default/pods/{name}/binding  (protobuf)
    │           PATCH /api/v1/namespaces/default/pods/{name}/status
    ▼
kube-scheduler binary  (--master http://localhost:8080 --leader-elect=false)
    │  binding decision (protobuf POST)
    ▼
adapter captures binding via channel → BatchDecision JSON → CloudSim
```

**Benefits:** no external cluster; zero polling delay (channel-based); clean state per run; portable to any machine with Go and the kube-scheduler binary.

### Package Structure (Go adapter after migration)

```
k8s-cloudsim-adapter/
├── main.go                    # CLI flags, router wiring, server startup
├── store/
│   └── store.go               # InMemoryStore: nodes, pods, resource version, broadcast channels
├── fakeapi/
│   └── handlers.go            # K8s API surface handlers (list, watch, binding, status patch, stubs)
├── scheduler/
│   └── scheduler.go           # SchedulingRound orchestration, pending-decision channel, timeout
├── communicator/
│   ├── communicator.go        # Simulation-facing handlers (/schedule, /nodes, /reset, /pods/{id}/status)
│   ├── conversion_utils.go    # CsNode/CsPod ↔ v1.Node/v1.Pod (KWOK fields removed)
│   ├── helper_functions.go    # extractNodeID, nodesEqual
│   └── k8s-simplified-structs.go  # CsNode, CsPod, SimulationSnapshot, BatchDecision
└── utils/
    └── general_utils.go
```

The `kube_client/` package is deleted entirely. The `fakeapi/` and `store/` packages replace it.

---

## Components and Interfaces

### InMemoryStore (`store/store.go`)

Central in-process state store. Replaces the KWOK cluster as the source of truth for nodes and pods.

```go
type InMemoryStore struct {
    mu              sync.RWMutex
    nodes           map[string]*v1.Node
    pods            map[string]*v1.Pod
    resourceVersion int64

    nodeEventCh     chan metav1.WatchEvent   // source channel for node broadcaster
    podEventCh      chan metav1.WatchEvent   // source channel for pod broadcaster
    nodeBroadcaster *BroadcastServer[metav1.WatchEvent]
    podBroadcaster  *BroadcastServer[metav1.WatchEvent]
}
```

Key operations:
- `CreateNode(node)`, `GetNodes() []v1.Node`, `GetNode(name) (*v1.Node, bool)`, `UpdateNode(name, node)`, `DeleteNode(name)`
- `CreatePod(pod)`, `GetPods() []v1.Pod`, `GetPod(name) (*v1.Pod, bool)`, `UpdatePod(name, pod)`, `DeletePod(name)`
- `Reset()` — atomically clears all state, resets resource version to 0, closes and recreates broadcast channels
- `SubscribeNodes() <-chan metav1.WatchEvent`, `SubscribePods() <-chan metav1.WatchEvent`

Every write increments `resourceVersion` and publishes the appropriate `WatchEvent` (ADDED / MODIFIED / DELETED) to the relevant broadcast channel.

### BroadcastServer (`store/broadcast.go`)

Generic fan-out channel multiplexer. Adapted from the `k8s-in-the-loop` reference implementation.

```go
type BroadcastServer[T any] struct { ... }
func NewBroadcastServer[T any](ctx context.Context, source <-chan T) *BroadcastServer[T]
func (s *BroadcastServer[T]) Subscribe() <-chan T
func (s *BroadcastServer[T]) CancelSubscription(ch <-chan T)
```

Each active WatchStream holds one subscription. On `Reset()`, the store cancels all subscriptions and creates a new broadcaster.

### FakeAPIServer (`fakeapi/handlers.go`)

Implements the Kubernetes API surface that the kube-scheduler requires. Registered on the same HTTP server as the simulation endpoints, under `/api/` and `/apis/` path prefixes.

**Active endpoints (backed by InMemoryStore):**

| Method | Path | Behaviour |
|--------|------|-----------|
| GET | `/api/v1/nodes` | Returns `v1.NodeList`; if `?watch=true`, upgrades to WatchStream |
| GET | `/api/v1/pods` | Returns `v1.PodList`; if `?watch=true`, upgrades to WatchStream |
| GET | `/api/v1/nodes/{name}` | Returns named node or HTTP 404 |
| PUT | `/api/v1/nodes/{name}` | Updates node in store (scheduler sets taints) |
| POST | `/api/v1/namespaces/default/pods/{name}/binding` | Decodes protobuf `v1.Binding`; records binding; decrements pending counter |
| PATCH | `/api/v1/namespaces/default/pods/{name}/status` | Records `Unschedulable` condition; decrements pending counter |
| GET | `/api/v1/namespaces` | Returns `v1.NamespaceList` with `default` |

**Stub endpoints (return valid empty lists, support `?watch=true` with idle WatchStream):**

`/api/v1/services`, `/api/v1/persistentvolumes`, `/apis/apps/v1/replicasets`, `/apis/apps/v1/statefulsets`, `/apis/apps/v1/daemonsets`, `/apis/storage.k8s.io/v1/storageclasses`, `/apis/storage.k8s.io/v1/csidrivers`, `/apis/storage.k8s.io/v1/csinodes`, `/apis/policy/v1/poddisruptionbudgets`, `/apis/batch/v1/jobs`

**WatchStream implementation:**

```go
func watchStream(w http.ResponseWriter, subscribe func() <-chan metav1.WatchEvent, cancel func(<-chan metav1.WatchEvent)) {
    flusher := w.(http.Flusher)
    w.Header().Set("Transfer-Encoding", "chunked")
    w.Header().Set("Content-Type", "application/json")
    ch := subscribe()
    defer cancel(ch)
    enc := json.NewEncoder(w)
    for event := range ch {
        enc.Encode(event)
        flusher.Flush()
    }
}
```

Each event is flushed immediately. The stream terminates when the channel is closed (on `Reset()`).

### SchedulingRound (`scheduler/scheduler.go`)

Manages the synchronisation between the simulation-facing `/schedule-pods` handler and the kube-scheduler's binding callbacks.

```go
type SchedulingRound struct {
    mu          sync.Mutex
    pending     int                    // countdown: decremented on each binding or failure
    decisions   chan BatchDecision      // unblocked when pending reaches 0
    assignments []PodAssignment        // accumulated scheduled pods
    failures    []PodFailure           // accumulated unschedulable pods
    timeout     time.Duration
}

type PodAssignment struct {
    PodID            int
    NodeID           int
    BindingTimestamp time.Time
}

type PodFailure struct {
    PodID  int
    Reason string
}
```

Lifecycle:
1. `Begin(n int)` — initialises a new round expecting `n` decisions; returns error if a round is already active (serialisation).
2. `RecordBinding(podName, nodeName string)` — records assignment with `time.Now()` as BindingTimestamp; decrements counter; if zero, sends `BatchDecision` on `decisions` channel.
3. `RecordFailure(podName, reason string)` — records failure; decrements counter; if zero, sends `BatchDecision`.
4. `Wait(ctx context.Context) (BatchDecision, error)` — blocks on `decisions` channel with timeout; returns HTTP 408 error on timeout.

A `sync.Mutex` guards the round state. A second `POST /schedule-pods` request calls `Begin()` while a round is active and receives an error, which the handler returns as HTTP 409.

### Simulation-facing Communicator (`communicator/communicator.go`)

Handles the CloudSim-facing HTTP endpoints. Replaces the current `Communicator` struct; no longer holds a `KubeClient`.

**Endpoints:**

| Method | Path | Description |
|--------|------|-------------|
| POST | `/schedule` | Accept `SimulationSnapshot`; apply node diff; delete completed pods; schedule pending pods; return `BatchDecision` |
| POST | `/nodes` | Accept `[]CsNode`; apply node diff against store; return HTTP 200 |
| POST | `/schedule-pods` | Accept `[]CsPod`; submit to scheduler; block for `BatchDecision`; return it |
| DELETE | `/reset` | Reset store and scheduling state |
| GET | `/pods/{id}/status` | Return in-memory `CsPod` by CloudSim ID |

`POST /pods/update-state` is removed entirely (no stub).

**`POST /schedule` flow:**

```
1. Decode SimulationSnapshot
2. Apply node diff (add missing, remove stale) against InMemoryStore
3. Delete pods for each completed cloudlet ID from InMemoryStore
4. If len(pendingPods) == 0: return empty BatchDecision immediately
5. Create v1.Pod objects in InMemoryStore for each pending pod
6. Call scheduler.Begin(len(pendingPods))
7. Block on scheduler.Wait(ctx) with 60 s timeout
8. Return BatchDecision as JSON
```

### Node and Pod Construction (updated `conversion_utils.go`)

KWOK-specific fields are removed. New node construction:

```go
func BuildNode(csNode CsNode, schedulerName string) *v1.Node {
    return &v1.Node{
        ObjectMeta: metav1.ObjectMeta{
            Name: fmt.Sprintf("csnode-%d", csNode.ID),
            Labels: map[string]string{
                "kubernetes.io/hostname": csNode.Name,
                "kubernetes.io/arch":    "amd64",
                "kubernetes.io/os":      "linux",
            },
            Annotations: map[string]string{
                "cloudsim.io/id":   fmt.Sprintf("%d", csNode.ID),
                "cloudsim.io/type": csNode.Type,
                "cloudsim.io/bw":   fmt.Sprintf("%d", csNode.BW),
                "cloudsim.io/size": fmt.Sprintf("%d", csNode.Size),
            },
        },
        Spec: corev1.NodeSpec{
            // No KWOK taints
        },
        Status: corev1.NodeStatus{
            Conditions: []corev1.NodeCondition{
                {Type: corev1.NodeReady, Status: corev1.ConditionTrue},
            },
            Allocatable: corev1.ResourceList{
                corev1.ResourceCPU:    resource.MustParse(fmt.Sprintf("%d", csNode.Pes)),
                corev1.ResourceMemory: resource.MustParse(fmt.Sprintf("%dMi", csNode.RAMAval)),
                corev1.ResourcePods:   resource.MustParse("110"),
            },
            Capacity: corev1.ResourceList{ /* same */ },
        },
    }
}
```

New pod construction (no KWOK toleration or NodeAffinity):

```go
func BuildPod(csPod CsPod, schedulerName string) *v1.Pod {
    return &v1.Pod{
        ObjectMeta: metav1.ObjectMeta{
            Name:      fmt.Sprintf("cspod-%d", csPod.ID),
            Namespace: "default",
            Annotations: map[string]string{
                "cloudsim.io/id": fmt.Sprintf("%d", csPod.ID),
            },
        },
        Spec: corev1.PodSpec{
            SchedulerName: schedulerName,
            // No KWOK tolerations or NodeAffinity
            Containers: []corev1.Container{{
                Name:  "fake-container",
                Image: "fake-image",
                Resources: corev1.ResourceRequirements{
                    Requests: corev1.ResourceList{
                        corev1.ResourceCPU: resource.MustParse(fmt.Sprintf("%d", csPod.Pes)),
                    },
                },
            }},
        },
    }
}
```

### Data Models

#### SimulationSnapshot (Go, JSON)

```go
type SimulationSnapshot struct {
    Nodes            []CsNode `json:"nodes"`
    Pods             []CsPod  `json:"pods"`
    CompletedPodIDs  []int    `json:"completedPodIds"`
}
```

#### BatchDecision (Go, JSON)

```go
type BatchDecision struct {
    Scheduled     []PodAssignment `json:"scheduled"`
    Unschedulable []PodFailure    `json:"unschedulable"`
}

type PodAssignment struct {
    PodID            int       `json:"podId"`
    NodeID           int       `json:"nodeId"`
    BindingTimestamp time.Time `json:"bindingTimestamp"`
}

type PodFailure struct {
    PodID  int    `json:"podId"`
    Reason string `json:"reason"`
}
```

#### PerformanceMetrics (Java)

```java
public class PerformanceMetrics {
    // Per-cloudlet timestamps
    private final Map<Integer, Instant> submissionTimestamps = new ConcurrentHashMap<>();
    private final List<Long> latenciesMs = new CopyOnWriteArrayList<>();

    // Throughput tracking
    private Instant firstSubmission;
    private Instant lastBinding;
    private int totalScheduled;

    public void recordSubmission(int cloudletId);
    public void recordBatchDecision(BatchDecisionResponse response);
    public double getAverageLatencyMs();
    public double getP99LatencyMs();
    public double getThroughputPodsPerSec();
    public void reset();
}
```

`SimulationMetrics` gains an optional constructor parameter:

```java
public SimulationMetrics(PowerDatacenterCustom dc, List<Vm> vms, PerformanceMetrics perf) { ... }
```

When `perf` is non-null, `printSummary()` appends average latency, P99 latency, and throughput lines.

---

## Correctness Properties

*A property is a characteristic or behavior that should hold true across all valid executions of a system — essentially, a formal statement about what the system should do. Properties serve as the bridge between human-readable specifications and machine-verifiable correctness guarantees.*

### Property 1: Node store round-trip

*For any* set of `v1.Node` objects created in the InMemoryStore, `GetNodes()` must return a list containing all of them; after deleting a node by name, `GetNodes()` must not contain it.

**Validates: Requirements 2.1**

### Property 2: Pod store round-trip

*For any* set of `v1.Pod` objects created in the InMemoryStore, `GetPods()` must return a list containing all of them; after updating a pod, `GetPods()` must reflect the updated values; after deleting a pod, `GetPods()` must not contain it.

**Validates: Requirements 2.2**

### Property 3: Resource version monotonicity

*For any* sequence of write operations (create, update, or delete) on the InMemoryStore, the resource version after each operation must be strictly greater than the resource version before that operation.

**Validates: Requirements 2.3**

### Property 4: Write operations publish correct watch events

*For any* node or pod, performing a create operation must publish an `ADDED` WatchEvent, an update must publish a `MODIFIED` WatchEvent, and a delete must publish a `DELETED` WatchEvent to the corresponding broadcast channel.

**Validates: Requirements 2.4, 6.2**

### Property 5: Reset produces empty store

*For any* InMemoryStore state (any number of nodes and pods), after calling `Reset()`, `GetNodes()` and `GetPods()` must both return empty lists, and the resource version must be 0.

**Validates: Requirements 2.5, 10.1, 10.2**

### Property 6: FakeAPIServer list endpoints reflect store contents

*For any* set of nodes stored in the InMemoryStore, `GET /api/v1/nodes` must return a `v1.NodeList` whose items contain exactly those nodes; equivalently for pods and `GET /api/v1/pods`.

**Validates: Requirements 3.1, 3.2**

### Property 7: Node get-by-name round-trip

*For any* node stored in the InMemoryStore, `GET /api/v1/nodes/{name}` must return that node; for any name not present in the store, the endpoint must return HTTP 404.

**Validates: Requirements 3.3**

### Property 8: Protobuf binding decoding preserves pod and node names

*For any* pod name and node name, encoding a `v1.Binding` as protobuf and POSTing it to `POST /api/v1/namespaces/default/pods/{name}/binding` must result in the adapter recording the correct pod-to-node assignment with a non-zero BindingTimestamp.

**Validates: Requirements 3.5, 4.2**

### Property 9: BatchDecision completeness

*For any* set of N pods submitted in a scheduling round, the resulting `BatchDecision` must contain exactly N entries across its `scheduled` and `unschedulable` lists combined, with each submitted pod ID appearing exactly once.

**Validates: Requirements 4.1, 4.2, 4.3**

### Property 10: SimulationSnapshot serialisation round-trip

*For any* `SimulationSnapshot` (any combination of nodes, pods, and completed pod IDs), JSON-serialising then deserialising must produce a value equal to the original.

**Validates: Requirements 5.1**

### Property 11: Node diff correctness

*For any* current set of nodes in the InMemoryStore and any incoming node list in a `SimulationSnapshot`, after applying the diff, `GetNodes()` must return exactly the nodes in the incoming list — no more, no fewer.

**Validates: Requirements 5.2**

### Property 12: Node and pod construction are free of KWOK fields

*For any* `CsNode`, the `BuildNode()` function must produce a `v1.Node` that: (a) has no taint with key `kwok.x-k8s.io/node`, (b) has no label `type=kwok`, (c) has a `Ready=True` condition, and (d) has a `cloudsim.io/id` annotation equal to the node's ID.

*For any* `CsPod` and scheduler name, the `BuildPod()` function must produce a `v1.Pod` that: (a) has no toleration for `kwok.x-k8s.io/node`, (b) has no `NodeAffinity` requiring `type=kwok`, (c) has `SchedulerName` equal to the configured scheduler name, and (d) has a `cloudsim.io/id` annotation equal to the pod's ID.

**Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5**

### Property 13: PerformanceMetrics latency computation

*For any* set of (submissionTimestamp, bindingTimestamp) pairs where bindingTimestamp >= submissionTimestamp, `getAverageLatencyMs()` must return the arithmetic mean of all (bindingTimestamp - submissionTimestamp) values in milliseconds, and `getP99LatencyMs()` must return the value at the 99th percentile of the same distribution.

**Validates: Requirements 8.3, 8.4, 8.5**

### Property 14: PerformanceMetrics reset clears all state

*For any* `PerformanceMetrics` instance with recorded data, after calling `reset()`, `getAverageLatencyMs()` must return 0, `getP99LatencyMs()` must return 0, and `getThroughputPodsPerSec()` must return 0.

**Validates: Requirements 8.9**

---

## Error Handling

### Adapter (Go)

| Scenario | Response |
|----------|----------|
| Malformed JSON in simulation request body | HTTP 400 with descriptive message |
| `POST /schedule-pods` while a round is already active | HTTP 409 `scheduling round already in progress` |
| Scheduling timeout (default 60 s) | HTTP 408 `scheduling timeout: not all pods resolved within 60s` |
| Node or pod not found in store | HTTP 404 |
| Protobuf decode failure on binding endpoint | HTTP 500; log error; do not decrement pending counter |
| `DELETE /reset` while a scheduling round is active | Cancel the active round (send empty BatchDecision with error), then reset store |
| WatchStream client disconnects | Cancel subscription; goroutine exits cleanly |

### Java Broker

| Scenario | Behaviour |
|----------|-----------|
| `POST /schedule` returns HTTP 408 | Log error; mark all pending cloudlets as failed; continue simulation |
| `POST /schedule` returns HTTP 4xx/5xx | Log error with status code; throw `RuntimeException` to surface the failure |
| `DELETE /reset` returns non-200 | Log warning (per Requirement 10.3); do not throw |
| `BatchDecision` contains unschedulable pods | Call `cloudSimAllocation()` only for scheduled pods; mark unschedulable cloudlets as failed |

### PerformanceMetrics (Java)

- `getAverageLatencyMs()` returns `0.0` when no latencies have been recorded (avoids division by zero).
- `getP99LatencyMs()` returns `0.0` when fewer than 100 samples are recorded (P99 is undefined for small samples; callers should check sample count).
- `getThroughputPodsPerSec()` returns `0.0` when elapsed time is zero.

---

## Testing Strategy

### Go Adapter — Unit Tests

Property-based tests use [**rapid**](https://github.com/flyingmutant/rapid) (Go property-based testing library). Each property test runs a minimum of 100 iterations.

**`store/store_test.go`** — InMemoryStore properties:
- Property 1: Node store round-trip (rapid generates random `v1.Node` slices)
- Property 2: Pod store round-trip (rapid generates random `v1.Pod` slices)
- Property 3: Resource version monotonicity (rapid generates random operation sequences)
- Property 4: Watch events (rapid generates random node/pod operations)
- Property 5: Reset produces empty store (rapid generates random pre-reset state)

Tag format: `// Feature: coubes-next-phase, Property N: <property text>`

**`fakeapi/handlers_test.go`** — FakeAPIServer properties:
- Property 6: List endpoints reflect store contents
- Property 7: Node get-by-name round-trip
- Property 8: Protobuf binding decoding

**`communicator/conversion_test.go`** — Construction properties:
- Property 10: SimulationSnapshot serialisation round-trip
- Property 11: Node diff correctness
- Property 12: Node and pod construction free of KWOK fields

**`scheduler/scheduler_test.go`** — SchedulingRound properties:
- Property 9: BatchDecision completeness

Example-based unit tests cover:
- Zero-pod snapshot returns empty BatchDecision immediately
- Timeout returns HTTP 408
- Concurrent `POST /schedule-pods` returns HTTP 409
- `POST /pods/update-state` returns HTTP 404 (endpoint removed)
- Stub endpoints return valid empty lists
- `GET /api/v1/namespaces` contains `default`

### Go Adapter — Race Detection

All store tests run with `go test -race` to verify concurrent access safety (Requirement 2.6).

### Java — Unit Tests (JUnit 5 + jqwik)

Property-based tests use [**jqwik**](https://jqwik.net/) (Java property-based testing library). Each `@Property` test runs a minimum of 100 tries.

**`PerformanceMetricsTest.java`**:
- Property 13: Latency computation (jqwik generates random timestamp pairs)
- Property 14: Reset clears all state (jqwik generates random recorded data)

Example-based tests:
- `getAverageLatencyMs()` returns 0.0 with no data
- `getP99LatencyMs()` returns 0.0 with no data
- `getThroughputPodsPerSec()` returns 0.0 with no data
- `SimulationMetrics.printSummary()` includes PerformanceMetrics values when provided

### Integration Tests

- Start adapter + kube-scheduler binary; verify scheduler completes initialisation handshake without errors (Requirement 1.4).
- Run `Fragmentation_Test`, `Performance_vs_Efficiency_Test`, `Undercrowding_Test` against the new adapter; verify `SimulationMetrics.printSummary()` is produced (Requirement 9.4).
- Verify `DELETE /reset` followed by a new simulation run produces clean results (Requirement 10).
