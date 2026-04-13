# Requirements Document

## Introduction

This document specifies the requirements for the next development phase of COUBES (Container Orchestration Universal Benchmark for Evaluating Schedulers). The project is a working proof-of-concept that bridges CloudSim 7G (Java 21) with a real Kubernetes scheduler via a Go adapter. Three objectives drive this phase:

1. **Fake API Server Migration** — replace the KWOK-emulated cluster with an in-process fake Kubernetes API server inside the adapter, eliminating Docker Desktop and KWOK as runtime dependencies.
2. **Batch-based Communication Protocol** — restructure the CloudSim ↔ adapter protocol into a clean, snapshot-oriented batch protocol that scales to hundreds of nodes and pods.
3. **Extended Metrics Module** — add a standalone performance-based metrics component (pod throughput and per-pod scheduling latency) alongside the existing decision-based metrics.

The `cloudsim-7.0/` and `k8s-in-the-loop/` directories are read-only references and must not be modified. The Java broker's external behaviour must remain compatible with existing test scenarios.

---

## Glossary

- **Adapter**: The Go HTTP middleware (`k8s-cloudsim-adapter/`) that bridges CloudSim and the Kubernetes scheduler.
- **Broker**: `Live_Kubernetes_Broker_Ex` — the CloudSim Java class that drives all K8s integration.
- **Cloudlet**: A CloudSim unit of work (task). Maps to a Kubernetes pod via the naming convention `cspod-{id}`.
- **CsPod**: The Go struct representing a CloudSim cloudlet as seen by the adapter.
- **CsNode**: The Go struct representing a CloudSim VM as seen by the adapter.
- **FakeAPIServer**: The in-process Go component that implements the Kubernetes API surface required by the kube-scheduler, backed entirely by in-memory storage.
- **InMemoryStore**: The in-process Go data store holding all node and pod state, replacing the KWOK cluster.
- **kube-scheduler**: The unmodified Kubernetes scheduler binary that connects to the FakeAPIServer.
- **KWOK**: Kubernetes Without Kubelet — the external cluster emulator used in the current implementation (to be eliminated).
- **PerformanceMetrics**: The new Java component tracking throughput and per-pod scheduling latency.
- **SchedulingRound**: One complete cycle of the Broker sending a SimulationSnapshot to the Adapter and receiving a BatchDecision.
- **SimulationSnapshot**: The complete state of nodes and pending pods sent by the Broker to the Adapter at the start of each SchedulingRound.
- **BatchDecision**: The complete set of pod-to-node assignments (and failures) returned by the Adapter to the Broker at the end of a SchedulingRound.
- **SubmissionTimestamp**: The wall-clock time recorded by the Broker immediately before sending a pod to the Adapter for scheduling.
- **BindingTimestamp**: The wall-clock time recorded by the Adapter at the moment the kube-scheduler posts a binding decision for a pod; embedded in the BatchDecision response and read by the Broker's PerformanceMetrics.
- **SchedulingLatency**: The elapsed wall-clock time between a pod's SubmissionTimestamp and its BindingTimestamp.
- **VM**: A CloudSim virtual machine. Maps to a Kubernetes node via the naming convention `csnode-{id}`.
- **WatchStream**: A long-lived HTTP chunked-transfer response used by the kube-scheduler's informer to receive real-time resource events.

---

## Requirements

### Requirement 1: Eliminate KWOK and Docker as Runtime Dependencies

**User Story:** As a researcher, I want to run COUBES simulations without Docker Desktop or a KWOK cluster, so that the framework is portable and can be executed on any machine with only the Go adapter and the kube-scheduler binary.

#### Acceptance Criteria

1. THE Adapter SHALL start successfully without a kubeconfig file, a running KWOK cluster, or Docker Desktop.
2. WHEN the Adapter starts, THE FakeAPIServer SHALL listen for kube-scheduler connections on a configurable TCP port (default 8080).
3. THE FakeAPIServer SHALL serve all Kubernetes API endpoints required by the kube-scheduler's startup list-and-watch phase, returning valid empty lists for resources that are not used by COUBES.
4. WHEN the kube-scheduler binary is started with `--master http://localhost:<port> --leader-elect=false`, THE FakeAPIServer SHALL complete the scheduler's initialisation handshake without errors.
5. THE Adapter SHALL accept the `--scheduler` flag to specify the scheduler name used when constructing pod specs, preserving the existing CLI interface.
6. IF the `--kubeconfig` flag is provided, THE Adapter SHALL ignore it without error, as kubeconfig is no longer required.

---

### Requirement 2: In-Memory Node and Pod Storage

**User Story:** As a developer, I want all node and pod state to be held in process memory inside the adapter, so that there is no external state store and each simulation run starts from a clean slate.

#### Acceptance Criteria

1. THE InMemoryStore SHALL store `v1.Node` objects indexed by node name and support create, get-all, and delete operations.
2. THE InMemoryStore SHALL store `v1.Pod` objects indexed by pod name and support create, get-all, update, and delete operations.
3. THE InMemoryStore SHALL maintain a monotonically increasing integer resource version counter, incrementing it on every write operation.
4. WHEN a node or pod is created, updated, or deleted, THE InMemoryStore SHALL publish a `metav1.WatchEvent` of type `ADDED`, `MODIFIED`, or `DELETED` respectively to a broadcast channel for that resource type.
5. WHEN the Adapter receives a `DELETE /reset` request, THE InMemoryStore SHALL atomically clear all nodes, pods, and pending scheduling state, and reset the resource version counter to zero.
6. THE InMemoryStore SHALL be safe for concurrent access from the kube-scheduler's watch goroutines and the Broker's HTTP request goroutines.

---

### Requirement 3: Kubernetes API Surface for the kube-scheduler

**User Story:** As a developer, I want the adapter to speak the Kubernetes API protocol that the kube-scheduler expects, so that the unmodified kube-scheduler binary can connect and make scheduling decisions.

#### Acceptance Criteria

1. THE FakeAPIServer SHALL implement `GET /api/v1/nodes` returning a `v1.NodeList` from the InMemoryStore; WHEN the `watch=true` query parameter is present, THE FakeAPIServer SHALL upgrade the response to a WatchStream.
2. THE FakeAPIServer SHALL implement `GET /api/v1/pods` returning a `v1.PodList` from the InMemoryStore; WHEN the `watch=true` query parameter is present, THE FakeAPIServer SHALL upgrade the response to a WatchStream.
3. THE FakeAPIServer SHALL implement `GET /api/v1/nodes/{name}` returning the named node from the InMemoryStore, or HTTP 404 if the node does not exist.
4. THE FakeAPIServer SHALL implement `PUT /api/v1/nodes/{name}` to allow the kube-scheduler to update node objects (e.g., setting taints).
5. THE FakeAPIServer SHALL implement `POST /api/v1/namespaces/default/pods/{name}/binding` to receive scheduling decisions from the kube-scheduler; THE FakeAPIServer SHALL decode the request body as protobuf-encoded `v1.Binding`.
6. THE FakeAPIServer SHALL implement `PATCH /api/v1/namespaces/default/pods/{name}/status` to receive scheduling failure notifications from the kube-scheduler.
7. THE FakeAPIServer SHALL implement `GET /api/v1/namespaces` returning a list containing at least the `default` namespace.
8. THE FakeAPIServer SHALL implement stub endpoints for all Kubernetes resource types queried by the kube-scheduler during startup (including but not limited to: `services`, `replicasets`, `statefulsets`, `persistentvolumes`, `storageclasses`, `csidrivers`, `csinodes`, `poddisruptionbudgets`, `daemonsets`, `jobs`); each stub SHALL return a valid empty list and SHALL support the `watch=true` parameter by returning a WatchStream that never sends events.
9. WHEN a WatchStream is active, THE FakeAPIServer SHALL flush each `metav1.WatchEvent` to the client immediately using chunked transfer encoding, without buffering.
10. THE FakeAPIServer SHALL NOT require the kube-scheduler to use leader election (`--leader-elect=false` is the expected startup flag).

---

### Requirement 4: Channel-based Scheduling Synchronisation

**User Story:** As a developer, I want the adapter to synchronise with the kube-scheduler using channels rather than polling loops, so that scheduling decisions are returned to the Broker with zero artificial delay.

#### Acceptance Criteria

1. WHEN the Adapter receives a `POST /schedule-pods` request containing N pods to schedule, THE Adapter SHALL initialise a pending-decision channel expecting exactly N binding or failure responses before unblocking.
2. WHEN the kube-scheduler posts a binding to `POST /api/v1/namespaces/default/pods/{name}/binding`, THE Adapter SHALL record the BindingTimestamp, record the binding in the InMemoryStore, and decrement the pending-decision counter; WHEN the counter reaches zero, THE Adapter SHALL send the complete BatchDecision (including all BindingTimestamps) on the pending-decision channel.
3. WHEN the kube-scheduler patches a pod status with an `Unschedulable` condition, THE Adapter SHALL record the failure and decrement the pending-decision counter; WHEN the counter reaches zero, THE Adapter SHALL send the complete BatchDecision on the pending-decision channel.
4. THE `POST /schedule-pods` handler SHALL block until the pending-decision channel delivers a BatchDecision, then return the BatchDecision as the HTTP response.
5. IF the pending-decision channel does not deliver a BatchDecision within a configurable timeout (default 60 seconds), THE Adapter SHALL return HTTP 408 to the Broker with a descriptive error message.
6. THE Adapter SHALL serialise concurrent `POST /schedule-pods` requests; a second request SHALL NOT be processed until the first SchedulingRound is complete.

---

### Requirement 5: Batch-based Simulation State Protocol

**User Story:** As a researcher, I want the Broker to send a complete simulation state snapshot per scheduling round and receive a complete batch of decisions, so that the protocol is clean, stateless, and scales to hundreds of nodes and pods.

#### Acceptance Criteria

1. THE Broker SHALL serialise the SimulationSnapshot as a JSON object containing: a list of all active VMs as CsNode objects, a list of all pending cloudlets as CsPod objects, and a list of cloudlet IDs that have completed since the last round.
2. WHEN the Broker sends a SimulationSnapshot to `POST /schedule-pods`, THE Adapter SHALL apply the node list as a diff against the InMemoryStore (adding missing nodes, removing stale nodes) before submitting pods to the kube-scheduler.
3. THE Adapter SHALL accept a SimulationSnapshot containing zero pending pods; in this case THE Adapter SHALL apply the node diff and return an empty BatchDecision immediately without contacting the kube-scheduler.
4. THE BatchDecision returned by the Adapter SHALL be a JSON object containing: a list of scheduled pod assignments (pod ID, node ID, and BindingTimestamp), and a list of unschedulable pod IDs with failure reasons.
5. THE Broker SHALL process the BatchDecision by binding each scheduled cloudlet to its assigned VM in CloudSim and marking each unschedulable cloudlet as failed.
6. THE Adapter SHALL expose `POST /nodes` as a standalone endpoint that accepts a list of CsNode objects and applies them as a diff against the InMemoryStore (adding missing nodes, removing stale nodes), returning HTTP 200 on success.
7. THE Adapter SHALL expose `POST /schedule` as a combined endpoint that accepts a SimulationSnapshot (node list + pending pods + completed cloudlet IDs) and performs node sync followed by pod scheduling in a single round-trip, returning a BatchDecision; this endpoint SHALL be equivalent to calling `POST /nodes` then `POST /schedule-pods` sequentially.
8. THE Broker SHALL use `POST /schedule` for normal scheduling rounds, replacing the current separate calls to `POST /nodes` and `POST /schedule-pods`; the standalone `POST /nodes` endpoint SHALL remain available for callers that require node sync without pod scheduling.
9. THE Adapter SHALL handle SimulationSnapshots containing at least 200 nodes and 500 pods without returning an error.

---

### Requirement 6: Pod Completion and Rescheduling via Batch Protocol

**User Story:** As a researcher, I want completed cloudlets to be reported to the adapter as part of the batch protocol, so that the adapter can delete finished pods and trigger rescheduling of pending pods in a single round-trip.

#### Acceptance Criteria

1. WHEN the Broker sends a SimulationSnapshot that includes one or more completed cloudlet IDs, THE Adapter SHALL delete the corresponding pods from the InMemoryStore before submitting the pending pod list to the kube-scheduler.
2. WHEN pods are deleted from the InMemoryStore, THE InMemoryStore SHALL publish `DELETED` WatchEvents so the kube-scheduler's informer cache is updated before the new pending pods are submitted.
3. IF the SimulationSnapshot contains both completed cloudlet IDs and pending pods, THE Adapter SHALL process deletions before pod submissions within the same SchedulingRound.
4. THE Adapter SHALL remove the `POST /pods/update-state` endpoint; the batch protocol (SimulationSnapshot with completed cloudlet IDs) fully replaces its function and no backward-compatible stub SHALL be retained.

---

### Requirement 7: Removal of KWOK-specific Pod and Node Annotations

**User Story:** As a developer, I want pod and node specs to be free of KWOK-specific labels, taints, and tolerations, so that the kube-scheduler can schedule pods onto nodes without KWOK infrastructure.

#### Acceptance Criteria

1. THE Adapter SHALL construct `v1.Node` objects for the InMemoryStore without the `kwok.x-k8s.io/node=fake` taint and without the `type=kwok` label.
2. THE Adapter SHALL construct `v1.Pod` objects for the InMemoryStore without the `kwok.x-k8s.io/node=fake:NoSchedule` toleration and without the `type=kwok` NodeAffinity requirement.
3. THE Adapter SHALL set `v1.Node.Status.Conditions` to include a `Ready=True` condition on every node, as the kube-scheduler will not schedule onto nodes lacking this condition.
4. THE Adapter SHALL preserve the `cloudsim.io/id` annotation on both nodes and pods as the canonical CloudSim ID mapping.
5. THE Adapter SHALL set `v1.Pod.Spec.SchedulerName` to the value of the `--scheduler` flag, defaulting to `default-scheduler`.

---

### Requirement 8: Standalone Performance Metrics Component

**User Story:** As a researcher, I want a standalone Java metrics component that tracks pod throughput and per-pod scheduling latency, so that I can measure the performance of the scheduling pipeline independently of the decision-based metrics.

#### Acceptance Criteria

1. THE PerformanceMetrics SHALL record a SubmissionTimestamp for each cloudlet at the moment the Broker sends it to the Adapter.
2. WHEN the Adapter returns a BatchDecision, THE PerformanceMetrics SHALL read the BindingTimestamp for each successfully scheduled cloudlet from the BatchDecision response.
3. THE PerformanceMetrics SHALL compute the SchedulingLatency for each cloudlet as the difference between its BindingTimestamp and its SubmissionTimestamp, expressed in milliseconds.
4. THE PerformanceMetrics SHALL expose a `getAverageLatencyMs()` method returning the arithmetic mean of all recorded SchedulingLatency values.
5. THE PerformanceMetrics SHALL expose a `getP99LatencyMs()` method returning the 99th-percentile SchedulingLatency across all recorded cloudlets.
6. THE PerformanceMetrics SHALL expose a `getThroughputPodsPerSec()` method returning the overall pod throughput computed as total scheduled pods divided by total elapsed wall-clock time in seconds.
7. THE PerformanceMetrics SHALL be a standalone class with no dependency on `PowerDatacenterCustom` or `SimulationMetrics`.
8. THE SimulationMetrics class SHALL accept an optional PerformanceMetrics instance and include its values in `printSummary()` output when provided.
9. THE PerformanceMetrics SHALL expose a `reset()` method that clears all recorded timestamps and counters, enabling reuse across multiple simulation runs in the same JVM process.

---

### Requirement 9: Backward Compatibility with Existing Test Scenarios

**User Story:** As a researcher, I want existing test scenarios (`Fragmentation_Test`, `Performance_vs_Efficiency_Test`, `Undercrowding_Test`) to continue running correctly after the migration, so that previously collected benchmark results remain reproducible.

#### Acceptance Criteria

1. THE Broker SHALL preserve its public API: constructor signatures, `sendResetRequestToControlPlane()`, and the CloudSim event-handling methods (`processVmCreateAck`, `processCloudletReturn`, `submitCloudlets`) SHALL remain unchanged.
2. THE Adapter SHALL continue to expose `DELETE /reset` with the same semantics as the current implementation.
3. THE Adapter SHALL continue to expose `GET /pods/{id}/status` with the same response schema as the current implementation.
4. WHEN an existing test scenario is run against the new adapter without modification, THE Broker SHALL complete the simulation and produce a `SimulationMetrics.printSummary()` output.
5. THE `disableDeallocation` flag on `PowerDatacenterCustom` SHALL continue to function as documented: WHEN `disableDeallocation` is `true`, THE PowerDatacenterCustom SHALL NOT destroy VMs after their cloudlets complete.

---

### Requirement 10: Adapter Reset and Clean-Run Guarantee

**User Story:** As a researcher, I want each simulation run to start from a completely clean adapter state, so that state from a previous run cannot affect the results of the next run.

#### Acceptance Criteria

1. WHEN the Adapter receives `DELETE /reset`, THE Adapter SHALL delete all pods and nodes from the InMemoryStore, cancel any active WatchStreams, and reset all scheduling synchronisation state.
2. AFTER a reset, THE FakeAPIServer SHALL respond to the kube-scheduler's next list request with empty node and pod lists.
3. THE Broker's `sendResetRequestToControlPlane()` method SHALL send `DELETE /reset` to the Adapter and SHALL log a warning if the response status is not 200.
4. WHEN the Adapter process is restarted, THE InMemoryStore SHALL initialise with empty state, providing the same guarantee as an explicit reset.

