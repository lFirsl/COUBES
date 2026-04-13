# Implementation Plan: COUBES Next Phase

## Overview

Three parallel workstreams implemented incrementally:
1. **Go adapter** — replace KWOK client with in-process fake K8s API server (`store/`, `fakeapi/`, `scheduler/` packages; updated `communicator/`; delete `kube_client/`)
2. **Batch protocol** — new `SimulationSnapshot` / `BatchDecision` round-trip replacing the current multi-call flow
3. **Java PerformanceMetrics** — standalone per-pod latency and throughput class; `SimulationMetrics` integration

Each task builds on the previous one. No code is left unintegrated.

---

## Tasks

- [x] 1. Create `store/` package — InMemoryStore and BroadcastServer
  - [x] 1.1 Implement `BroadcastServer[T]` in `store/broadcast.go`
    - Generic fan-out channel multiplexer: `NewBroadcastServer`, `Subscribe`, `CancelSubscription`
    - Goroutine reads from source channel and fans out to all subscriber channels
    - Closing the source channel terminates the goroutine and closes all subscriber channels
    - _Requirements: 2.4, 2.6_

  - [x] 1.2 Write property test for BroadcastServer (Property 4 — watch events)
    - **Property 4: Write operations publish correct watch events**
    - **Validates: Requirements 2.4, 6.2**
    - Tag: `// Feature: coubes-next-phase, Property 4`
    - Use `rapid` to generate random sequences of node/pod operations; assert ADDED/MODIFIED/DELETED events arrive on subscriber channel
    - Run with `go test -race`

  - [x] 1.3 Implement `InMemoryStore` in `store/store.go`
    - Fields: `nodes map[string]*v1.Node`, `pods map[string]*v1.Pod`, `resourceVersion int64`, node/pod event channels, node/pod `BroadcastServer`
    - Implement `CreateNode`, `GetNodes`, `GetNode`, `UpdateNode`, `DeleteNode`
    - Implement `CreatePod`, `GetPods`, `GetPod`, `UpdatePod`, `DeletePod`
    - Every write increments `resourceVersion` and publishes the appropriate `WatchEvent` (ADDED/MODIFIED/DELETED)
    - Implement `SubscribeNodes() <-chan metav1.WatchEvent` and `SubscribePods() <-chan metav1.WatchEvent`
    - Implement `Reset()`: atomically clears all state, resets `resourceVersion` to 0, closes and recreates broadcast channels
    - Protect all operations with `sync.RWMutex`
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 2.6_

  - [x] 1.4 Write property test for node store round-trip (Property 1)
    - **Property 1: Node store round-trip**
    - **Validates: Requirements 2.1**
    - Tag: `// Feature: coubes-next-phase, Property 1`
    - Use `rapid` to generate random `v1.Node` slices; assert `GetNodes()` contains all created nodes; assert deleted node absent
    - Run with `go test -race`

  - [x] 1.5 Write property test for pod store round-trip (Property 2)
    - **Property 2: Pod store round-trip**
    - **Validates: Requirements 2.2**
    - Tag: `// Feature: coubes-next-phase, Property 2`
    - Use `rapid` to generate random `v1.Pod` slices; assert create/update/delete semantics
    - Run with `go test -race`

  - [x] 1.6 Write property test for resource version monotonicity (Property 3)
    - **Property 3: Resource version monotonicity**
    - **Validates: Requirements 2.3**
    - Tag: `// Feature: coubes-next-phase, Property 3`
    - Use `rapid` to generate random operation sequences; assert `resourceVersion` strictly increases after each write
    - Run with `go test -race`

  - [x] 1.7 Write property test for Reset produces empty store (Property 5)
    - **Property 5: Reset produces empty store**
    - **Validates: Requirements 2.5, 10.1, 10.2**
    - Tag: `// Feature: coubes-next-phase, Property 5`
    - Use `rapid` to generate arbitrary pre-reset state; assert `GetNodes()` and `GetPods()` both empty and `resourceVersion == 0` after `Reset()`
    - Run with `go test -race`

- [x] 2. Checkpoint — store package complete
  - Ensure all tests pass (`go test -race ./store/...`), ask the user if questions arise.

- [x] 3. Create `fakeapi/` package — Kubernetes API surface
  - [x] 3.1 Implement list and watch handlers for nodes and pods in `fakeapi/handlers.go`
    - `GET /api/v1/nodes`: return `v1.NodeList` from store; if `?watch=true` upgrade to `watchStream`
    - `GET /api/v1/pods`: return `v1.PodList` from store; if `?watch=true` upgrade to `watchStream`
    - `watchStream` helper: set `Transfer-Encoding: chunked`, subscribe to store broadcast, JSON-encode and flush each event, cancel subscription on client disconnect
    - _Requirements: 3.1, 3.2, 3.9_

  - [x] 3.2 Implement node get-by-name and node update handlers
    - `GET /api/v1/nodes/{name}`: return named node or HTTP 404
    - `PUT /api/v1/nodes/{name}`: update node in store (scheduler sets taints)
    - _Requirements: 3.3, 3.4_

  - [x] 3.3 Implement binding and status-patch handlers
    - `POST /api/v1/namespaces/default/pods/{name}/binding`: decode protobuf `v1.Binding`; call `scheduler.RecordBinding(podName, nodeName)`
    - `PATCH /api/v1/namespaces/default/pods/{name}/status`: parse `Unschedulable` condition; call `scheduler.RecordFailure(podName, reason)`
    - On protobuf decode failure: HTTP 500, log error, do NOT decrement pending counter
    - _Requirements: 3.5, 3.6, 4.2, 4.3_

  - [x] 3.4 Implement namespace endpoint and all stub endpoints
    - `GET /api/v1/namespaces`: return `v1.NamespaceList` containing `default`
    - Stub endpoints returning valid empty lists with idle `?watch=true` support: `/api/v1/services`, `/api/v1/persistentvolumes`, `/apis/apps/v1/replicasets`, `/apis/apps/v1/statefulsets`, `/apis/apps/v1/daemonsets`, `/apis/storage.k8s.io/v1/storageclasses`, `/apis/storage.k8s.io/v1/csidrivers`, `/apis/storage.k8s.io/v1/csinodes`, `/apis/policy/v1/poddisruptionbudgets`, `/apis/batch/v1/jobs`
    - _Requirements: 3.7, 3.8, 1.3_

  - [x] 3.5 Write property test for FakeAPIServer list endpoints (Property 6)
    - **Property 6: FakeAPIServer list endpoints reflect store contents**
    - **Validates: Requirements 3.1, 3.2**
    - Tag: `// Feature: coubes-next-phase, Property 6`
    - Use `rapid` to generate random node/pod sets; assert `GET /api/v1/nodes` and `GET /api/v1/pods` return exactly those items

  - [x] 3.6 Write property test for node get-by-name round-trip (Property 7)
    - **Property 7: Node get-by-name round-trip**
    - **Validates: Requirements 3.3**
    - Tag: `// Feature: coubes-next-phase, Property 7`
    - Use `rapid` to generate random node names; assert present nodes return 200 with correct body; absent names return 404

  - [x] 3.7 Write property test for protobuf binding decoding (Property 8)
    - **Property 8: Protobuf binding decoding preserves pod and node names**
    - **Validates: Requirements 3.5, 4.2**
    - Tag: `// Feature: coubes-next-phase, Property 8`
    - Use `rapid` to generate random pod/node name pairs; encode as protobuf `v1.Binding`; POST to binding endpoint; assert correct assignment recorded with non-zero BindingTimestamp

- [x] 4. Checkpoint — fakeapi package complete
  - Ensure all tests pass (`go test -race ./fakeapi/...`), ask the user if questions arise.

- [x] 5. Create `scheduler/` package — SchedulingRound synchronisation
  - [x] 5.1 Implement `SchedulingRound` in `scheduler/scheduler.go`
    - Fields: `mu sync.Mutex`, `pending int`, `decisions chan BatchDecision`, `assignments []PodAssignment`, `failures []PodFailure`, `timeout time.Duration`, `active bool`
    - `Begin(n int) error`: initialise round expecting `n` decisions; return error if round already active (HTTP 409 guard)
    - `RecordBinding(podName, nodeName string)`: record assignment with `time.Now()` as BindingTimestamp; decrement counter; send `BatchDecision` when counter reaches 0
    - `RecordFailure(podName, reason string)`: record failure; decrement counter; send `BatchDecision` when counter reaches 0
    - `Wait(ctx context.Context) (BatchDecision, error)`: block on `decisions` channel; return HTTP 408 error on timeout
    - `Reset()`: cancel active round with empty BatchDecision; reset state
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 4.6_

  - [x] 5.2 Write property test for BatchDecision completeness (Property 9)
    - **Property 9: BatchDecision completeness**
    - **Validates: Requirements 4.1, 4.2, 4.3**
    - Tag: `// Feature: coubes-next-phase, Property 9`
    - Use `rapid` to generate random sets of N pod names; simulate N `RecordBinding` or `RecordFailure` calls; assert `BatchDecision` contains exactly N entries with each pod ID appearing exactly once

- [x] 6. Update `communicator/` package — new structs, conversion utils, and simulation endpoints
  - [x] 6.1 Add `SimulationSnapshot` and `BatchDecision` structs to `k8s-simplified-structs.go`
    - `SimulationSnapshot { Nodes []CsNode, Pods []CsPod, CompletedPodIDs []int }`
    - `BatchDecision { Scheduled []PodAssignment, Unschedulable []PodFailure }`
    - `PodAssignment { PodID int, NodeID int, BindingTimestamp time.Time }`
    - `PodFailure { PodID int, Reason string }`
    - Remove extender-specific structs (`ExtenderArgs`, `ExtenderFilterResult`, `HostPriority`, `HostPriorityList`) that are no longer used
    - _Requirements: 5.1, 5.4_

  - [x] 6.2 Replace `conversion_utils.go` — remove KWOK fields, add `BuildNode` and `BuildPod`
    - Delete `SendFakePodFromCs`, `SendFakePodsFromCs`, `SendFakeNodeFromCs`, `SendFakeNodesFromCs` (KWOK-specific senders)
    - Add `BuildNode(csNode CsNode, schedulerName string) *v1.Node`: no KWOK taint, no `type=kwok` label, `Ready=True` condition, `cloudsim.io/id` annotation
    - Add `BuildPod(csPod CsPod, schedulerName string) *v1.Pod`: no KWOK toleration, no NodeAffinity, correct `SchedulerName`, `cloudsim.io/id` annotation
    - Keep `ConvertToCsPod`, `ConvertToCsPods`, `ConvertToCsNode`, `ConvertToCsNodes` (still needed for reading back from store)
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

  - [x] 6.3 Write property test for node and pod construction free of KWOK fields (Property 12)
    - **Property 12: Node and pod construction are free of KWOK fields**
    - **Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5**
    - Tag: `// Feature: coubes-next-phase, Property 12`
    - Use `rapid` to generate random `CsNode` and `CsPod` values; assert `BuildNode` output has no KWOK taint/label, has `Ready=True`, has correct `cloudsim.io/id`; assert `BuildPod` output has no KWOK toleration/NodeAffinity, has correct `SchedulerName` and `cloudsim.io/id`

  - [x] 6.4 Write property test for SimulationSnapshot serialisation round-trip (Property 10)
    - **Property 10: SimulationSnapshot serialisation round-trip**
    - **Validates: Requirements 5.1**
    - Tag: `// Feature: coubes-next-phase, Property 10`
    - Use `rapid` to generate random `SimulationSnapshot` values; assert JSON marshal → unmarshal produces equal value

  - [x] 6.5 Write property test for node diff correctness (Property 11)
    - **Property 11: Node diff correctness**
    - **Validates: Requirements 5.2**
    - Tag: `// Feature: coubes-next-phase, Property 11`
    - Use `rapid` to generate random current store state and incoming node lists; apply diff; assert `GetNodes()` returns exactly the incoming list

  - [x] 6.6 Rewrite `communicator.go` — new `Communicator` struct wired to `InMemoryStore` and `SchedulingRound`
    - Remove `kubeClient` field; add `store *store.InMemoryStore`, `round *scheduler.SchedulingRound`, `schedulerName string`
    - Update `NewCommunicator` signature accordingly
    - Implement `HandleNodes(w, r)`: decode `[]CsNode`; apply node diff against store (add missing, remove stale); return HTTP 200
    - Implement `HandleSchedule(w, r)` for `POST /schedule`: decode `SimulationSnapshot`; apply node diff; delete completed pods from store; if no pending pods return empty `BatchDecision`; otherwise create pods in store, call `round.Begin(n)`, block on `round.Wait(ctx)`, return `BatchDecision`
    - Implement `HandleSchedulePods(w, r)` for `POST /schedule-pods`: decode `[]CsPod`; create pods in store; call `round.Begin(n)`; block on `round.Wait(ctx)`; return `BatchDecision`; return HTTP 409 if round already active
    - Implement `HandleReset(w, r)`: call `round.Reset()` then `store.Reset()`; return HTTP 200
    - Implement `HandlePodStatus(w, r)`: look up pod in store by CloudSim ID; return `CsPod` JSON or HTTP 404
    - Remove `HandleDeleteCloudletAndWait` (endpoint deleted per Requirement 6.4)
    - _Requirements: 5.2, 5.3, 5.6, 5.7, 5.8, 6.1, 6.2, 6.3, 9.2, 9.3, 10.1_

- [x] 7. Checkpoint — communicator package complete
  - Ensure all tests pass (`go test -race ./communicator/...`), ask the user if questions arise.

- [x] 8. Rewrite `main.go` — wire all packages together, remove `kube_client` dependency
  - Remove `kube_client` import and all `kube_client.*` handler registrations
  - Instantiate `store.NewInMemoryStore()`, `scheduler.NewSchedulingRound(60 * time.Second)`, `communicator.NewCommunicator(store, round, schedulerName)`
  - Instantiate `fakeapi.NewFakeAPIHandler(store, round)`
  - Register simulation-facing routes: `POST /schedule`, `POST /nodes`, `POST /schedule-pods`, `DELETE /reset`, `GET /pods/{id}/status`
  - Register K8s API routes under `/api/` and `/apis/` prefixes using `fakeapi` handlers
  - Remove `--kubeconfig` flag (or accept and ignore it per Requirement 1.6); keep `--scheduler` flag
  - Remove `extenderURL` variable (no longer needed)
  - _Requirements: 1.1, 1.2, 1.5, 1.6_

- [x] 9. Delete `kube_client/` package
  - Delete `kube_client/http_utils.go`, `kube_client/kube_client.go`, `kube_client/node_functions.go`, `kube_client/pod_functions.go`
  - Update `go.mod` / `go.sum` if any dependencies become unused after removal
  - Verify `go build ./...` succeeds with no references to `kube_client`
  - _Requirements: 1.1_

- [x] 10. Checkpoint — Go adapter builds and unit tests pass
  - Run `go build ./...` and `go test -race ./...` from `k8s-cloudsim-adapter/`; ensure zero failures; ask the user if questions arise.

- [x] 11. Implement Java `PerformanceMetrics` class
  - [x] 11.1 Create `PerformanceMetrics.java` in `src/main/java/org/example/metrics/`
    - Fields: `ConcurrentHashMap<Integer, Instant> submissionTimestamps`, `CopyOnWriteArrayList<Long> latenciesMs`, `Instant firstSubmission`, `Instant lastBinding`, `AtomicInteger totalScheduled`
    - `recordSubmission(int cloudletId)`: store `Instant.now()` keyed by cloudlet ID; set `firstSubmission` if first call
    - `recordBatchDecision(BatchDecisionResponse response)`: for each scheduled entry, compute latency from stored submission timestamp to `bindingTimestamp` from response; add to `latenciesMs`; update `lastBinding` and `totalScheduled`
    - `getAverageLatencyMs()`: arithmetic mean of `latenciesMs`; return `0.0` if empty
    - `getP99LatencyMs()`: 99th-percentile of sorted `latenciesMs`; return `0.0` if fewer than 100 samples
    - `getThroughputPodsPerSec()`: `totalScheduled / elapsedSeconds(firstSubmission, lastBinding)`; return `0.0` if elapsed is zero
    - `reset()`: clear all fields to initial state
    - No dependency on `PowerDatacenterCustom` or `SimulationMetrics`
    - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.9_

  - [x] 11.2 Add `BatchDecisionResponse` Java DTO in `src/main/java/org/example/kubernetes_broker/`
    - Fields matching `BatchDecision` JSON: `List<PodAssignment> scheduled`, `List<PodFailure> unschedulable`
    - `PodAssignment`: `int podId`, `int nodeId`, `Instant bindingTimestamp`
    - `PodFailure`: `int podId`, `String reason`
    - Jackson annotations for JSON deserialisation (`@JsonProperty`, `@JsonDeserialize` for `Instant`)
    - _Requirements: 5.4_

  - [x] 11.3 Write property test for PerformanceMetrics latency computation (Property 13)
    - **Property 13: PerformanceMetrics latency computation**
    - **Validates: Requirements 8.3, 8.4, 8.5**
    - Tag: `// Feature: coubes-next-phase, Property 13`
    - Use `jqwik` `@Property` with `@ForAll` to generate random lists of (submissionTimestamp, bindingTimestamp) pairs where bindingTimestamp >= submissionTimestamp; assert `getAverageLatencyMs()` equals arithmetic mean; assert `getP99LatencyMs()` equals 99th percentile (for ≥100 samples)
    - Minimum 100 tries

  - [x] 11.4 Write property test for PerformanceMetrics reset clears all state (Property 14)
    - **Property 14: PerformanceMetrics reset clears all state**
    - **Validates: Requirements 8.9**
    - Tag: `// Feature: coubes-next-phase, Property 14`
    - Use `jqwik` `@Property` to generate arbitrary recorded data; call `reset()`; assert `getAverageLatencyMs() == 0.0`, `getP99LatencyMs() == 0.0`, `getThroughputPodsPerSec() == 0.0`
    - Minimum 100 tries

- [x] 12. Update `SimulationMetrics` to accept optional `PerformanceMetrics`
  - Add constructor `SimulationMetrics(PowerDatacenterCustom dc, List<Vm> vms, PerformanceMetrics perf)`
  - Keep existing two-arg constructor `SimulationMetrics(PowerDatacenterCustom dc, List<Vm> vms)` unchanged (backward compatibility)
  - In `printSummary()`: when `perf` is non-null, append average latency, P99 latency, and throughput lines
  - _Requirements: 8.8, 9.1_

- [x] 13. Update `Live_Kubernetes_Broker_Ex` — adopt batch protocol, add `PerformanceMetrics` support
  - [x] 13.1 Replace `submitCloudletBatchToMiddleware` with `POST /schedule` call
    - Build `SimulationSnapshot` JSON: nodes from `getGuestsCreatedList()`, pending cloudlets as `CsPod` list, completed cloudlet IDs (initially empty on first call)
    - POST to `CONTROL_PLANE_URL + "/schedule"`; deserialise response as `BatchDecisionResponse`
    - On HTTP 408: log error; mark all pending cloudlets as failed; continue simulation
    - On other 4xx/5xx: log error with status code; throw `RuntimeException`
    - _Requirements: 5.1, 5.5, 5.8_

  - [x] 13.2 Replace `updateMiddleware` / `deleteCloudletAndWait` with completed-IDs in snapshot
    - In `processCloudletReturn`: accumulate completed cloudlet IDs in a `List<Integer> completedSinceLastRound`
    - On next `submitCloudlets()` call, include `completedSinceLastRound` in the `SimulationSnapshot`; clear the list after sending
    - Remove `updateMiddleware`, `deleteCloudletAndWait`, and `POST /pods/update-state` call
    - _Requirements: 6.1, 6.3_

  - [x] 13.3 Wire `PerformanceMetrics` into the broker (optional integration)
    - Add optional `PerformanceMetrics perf` field (null by default)
    - Add `setPerformanceMetrics(PerformanceMetrics perf)` setter
    - Call `perf.recordSubmission(cloudletId)` for each cloudlet before sending the snapshot
    - Call `perf.recordBatchDecision(response)` after receiving `BatchDecisionResponse`
    - Guard all `perf.*` calls with null check
    - _Requirements: 8.1, 8.2_

  - [x] 13.4 Update `processScheduledPodsResponse` to consume `BatchDecisionResponse`
    - Replace `ArrayNode` parsing with `BatchDecisionResponse` deserialisation
    - For each `PodAssignment` in `scheduled`: call `submitCloudletToVmInCloudSim(cloudlet, nodeId)`
    - For each `PodFailure` in `unschedulable`: mark cloudlet as `FAILED`
    - Remove old `status` string switch (`"Scheduled"`, `"Unschedulable"`, `"Unknown"`)
    - _Requirements: 5.5_

  - [x] 13.5 Update `sendResetRequestToControlPlane` to log warning on non-200
    - Change existing silent failure to `Log.println("WARNING: ...")` on non-200 response
    - _Requirements: 10.3_

- [x] 14. Checkpoint — Java broker and metrics compile and tests pass
  - Run `mvn test` in `cloudsim-experimental/`; ensure zero failures; ask the user if questions arise.

- [x] 15. Verify existing test scenarios remain compatible
  - [x] 15.1 Confirm `Fragmentation_Test`, `Performance_vs_Efficiency_Test`, `Undercrowding_Test` compile without modification
    - Check that broker public API (`constructor`, `sendResetRequestToControlPlane`, `processVmCreateAck`, `processCloudletReturn`, `submitCloudlets`) signatures are unchanged
    - Check that `SimulationMetrics` two-arg constructor still compiles
    - Check that `PowerDatacenterCustom.disableDeallocation` flag is untouched
    - _Requirements: 9.1, 9.4, 9.5_

  - [x] 15.2 Add example-based unit tests for key adapter behaviours
    - Zero-pod `SimulationSnapshot` returns empty `BatchDecision` immediately (no scheduler contact)
    - Scheduling timeout returns HTTP 408
    - Concurrent `POST /schedule-pods` while round active returns HTTP 409
    - `POST /pods/update-state` returns HTTP 404 (endpoint removed)
    - Stub endpoints (`/api/v1/services`, etc.) return valid empty lists with HTTP 200
    - `GET /api/v1/namespaces` response contains `default`
    - `DELETE /reset` followed by `GET /api/v1/nodes` returns empty list
    - _Requirements: 1.3, 4.5, 4.6, 6.4, 10.1, 10.2_

- [x] 16. Final checkpoint — full suite passes
  - Run `go test -race ./...` from `k8s-cloudsim-adapter/` and `mvn test` from `cloudsim-experimental/`; ensure all tests pass; ask the user if questions arise.

---

## Notes

- Tasks marked with `*` are optional and can be skipped for a faster MVP
- Each task references specific requirements for traceability
- Property tests use `rapid` (Go) and `jqwik` (Java); run with `-race` flag for Go tests
- `kube_client/` is deleted in task 9 — do not reference it in any new code
- Do not modify `cloudsim-7.0/` or `k8s-in-the-loop/` at any point
- The `--kubeconfig` flag may be accepted and silently ignored to avoid breaking existing run scripts
