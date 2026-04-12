# Requirements Document

## Introduction

This document specifies the requirements for two additions to the COUBES adapter and scheduler infrastructure:

1. **Adapter Test Mode (Round-Robin Scheduling)** — a standalone operating mode for the Go adapter (`k8s-cloudsim-adapter/`) that performs round-robin pod-to-node assignment internally, without connecting to a kube-scheduler or a KWOK cluster. This enables isolated integration testing of the CloudSim ↔ adapter HTTP protocol without any external infrastructure.

2. **Scheduler Dockerfile** — a well-documented, configurable Dockerfile (and supporting configuration) for deploying a named custom `kube-scheduler` instance alongside the COUBES adapter. This replaces and improves upon the existing `second-scheduler/` Dockerfile, making it easy to switch between scheduling profiles (e.g. `LeastAllocated` spreading vs `MostAllocated` bin-packing).

The `cloudsim-7.0/` and `k8s-in-the-loop/` directories are read-only references and must not be modified.

---

## Glossary

- **Adapter**: The Go HTTP middleware (`k8s-cloudsim-adapter/`) that bridges CloudSim and the Kubernetes scheduler.
- **BatchDecision**: The JSON response returned by the Adapter to the Broker containing pod-to-node assignments and failures.
- **Broker**: `Live_Kubernetes_Broker_Ex` — the CloudSim Java class that drives all K8s integration.
- **Cloudlet**: A CloudSim unit of work (task). Maps to a Kubernetes pod via the naming convention `cspod-{id}`.
- **CsNode**: The Go struct representing a CloudSim VM as seen by the Adapter.
- **CsPod**: The Go struct representing a CloudSim cloudlet as seen by the Adapter.
- **InMemoryStore**: The in-process Go data store holding all node and pod state.
- **kube-scheduler**: The unmodified Kubernetes scheduler binary that connects to the Adapter's fake API server.
- **KubeSchedulerConfiguration**: The YAML configuration file consumed by `kube-scheduler` via `--config`, specifying scheduler name, leader election, and scoring plugins.
- **SchedulerDockerfile**: The new Dockerfile (and supporting files) for building and running a named custom kube-scheduler container.
- **SchedulingProfile**: A named set of scoring plugin configurations within a `KubeSchedulerConfiguration` (e.g. `default-scheduler` with `LeastAllocated`, or `my-scheduler` with `MostAllocated`).
- **TestModeScheduler**: The internal round-robin scheduler used by the Adapter when running in test mode; it assigns pods to nodes without contacting any external scheduler.
- **VM**: A CloudSim virtual machine. Maps to a Kubernetes node via the naming convention `csnode-{id}`.

---

## Requirements

### Requirement 1: Adapter Test Mode CLI Flag

**User Story:** As a developer, I want to start the adapter in a standalone test mode via a CLI flag, so that I can run integration tests against the adapter without Docker, KWOK, or a running kube-scheduler.

#### Acceptance Criteria

1. THE Adapter SHALL accept a `--test-mode` boolean flag; WHEN `--test-mode` is set, THE Adapter SHALL start in test mode.
2. WHEN the Adapter starts in test mode, THE Adapter SHALL log a clearly visible message indicating that test mode is active and that no kube-scheduler connection will be made.
3. WHEN the Adapter starts in normal mode (i.e. `--test-mode` is not set), THE Adapter SHALL behave identically to the current implementation, with no change to existing behaviour.
4. THE `--scheduler` flag SHALL remain valid in test mode; WHEN `--test-mode` is set, THE Adapter SHALL use the `--scheduler` value only for constructing pod specs (e.g. `pod.Spec.SchedulerName`), not for routing to an external scheduler.
5. IF `--test-mode` is set alongside `--kubeconfig`, THE Adapter SHALL ignore `--kubeconfig` without error.

---

### Requirement 2: Round-Robin Pod Scheduling in Test Mode

**User Story:** As a developer, I want the adapter in test mode to assign pods to nodes using round-robin, so that I can verify the CloudSim ↔ adapter HTTP protocol end-to-end without a real scheduler.

#### Acceptance Criteria

1. WHEN the Adapter is in test mode and receives a `POST /schedule-pods` request containing N pods and the InMemoryStore contains M nodes (M ≥ 1), THE TestModeScheduler SHALL assign each pod to a node by cycling through the node list in a deterministic round-robin order.
2. THE TestModeScheduler SHALL assign pod `i` (zero-indexed in the order received) to node `nodes[i mod M]`, where `nodes` is the list of nodes sorted by node name in ascending lexicographic order.
3. WHEN the Adapter is in test mode and receives a `POST /schedule-pods` request but the InMemoryStore contains zero nodes, THE Adapter SHALL return all pods as unschedulable with the reason `"no nodes available"`.
4. WHEN the Adapter is in test mode, THE TestModeScheduler SHALL record a `BindingTimestamp` for each assignment at the moment the assignment is computed.
5. THE TestModeScheduler SHALL produce a `BatchDecision` with the same JSON schema as the normal-mode scheduler, so that the Broker requires no code changes to work with test mode.
6. WHEN the Adapter is in test mode, THE `POST /schedule-pods` handler SHALL return the `BatchDecision` synchronously without waiting for any external process.

---

### Requirement 3: Full HTTP API Availability in Test Mode

**User Story:** As a developer, I want all adapter HTTP endpoints to be available in test mode, so that the full CloudSim ↔ adapter integration can be exercised without modification to the Broker.

#### Acceptance Criteria

1. WHEN the Adapter is in test mode, THE Adapter SHALL expose `POST /nodes` with the same request/response contract as normal mode.
2. WHEN the Adapter is in test mode, THE Adapter SHALL expose `POST /schedule-pods` with the same request/response contract as normal mode, using the TestModeScheduler to produce assignments.
3. THE Adapter SHALL expose `POST /pods/update-state` in both normal mode and test mode with the same request/response contract; WHEN the Adapter is in test mode, THE `POST /pods/update-state` handler SHALL update the pod state in the InMemoryStore and return any newly-schedulable pods using the TestModeScheduler, without contacting any external process.
4. WHEN the Adapter is in test mode, THE Adapter SHALL expose `DELETE /reset` with the same semantics as normal mode: clearing all nodes, pods, and scheduling state from the InMemoryStore.
5. WHEN the Adapter is in test mode, THE Adapter SHALL expose `GET /pods/{id}/status` with the same response schema as normal mode.
6. WHEN the Adapter is in test mode, THE Adapter SHALL NOT start the fake Kubernetes API server routes (e.g. `/api/v1/nodes`, `/api/v1/pods`, binding endpoints) that are intended for the kube-scheduler, as no kube-scheduler will connect.

---

### Requirement 4: Round-Robin Correctness Properties

**User Story:** As a developer, I want the round-robin assignment to be deterministic and verifiable, so that test results are reproducible and I can assert exact pod-to-node mappings in automated tests.

#### Acceptance Criteria

1. FOR ALL inputs of N pods and M nodes (M ≥ 1), THE TestModeScheduler SHALL assign exactly N pods in total, with no pod assigned more than once and no pod left unassigned.
2. FOR ALL inputs of N pods and M nodes (M ≥ 1), THE TestModeScheduler SHALL assign `ceil(N/M)` or `floor(N/M)` pods to each node (i.e. the assignment is as balanced as possible).
3. WHEN the same list of pods and nodes is submitted twice in sequence (with a reset between runs), THE TestModeScheduler SHALL produce identical assignments both times.
4. WHEN N pods are submitted and M = 1 node is available, THE TestModeScheduler SHALL assign all N pods to that single node.
5. WHEN N = M (one pod per node), THE TestModeScheduler SHALL assign exactly one pod to each node.

---

### Requirement 5: Scheduler Dockerfile — Base Image and Scheduler Binary

**User Story:** As a researcher, I want a Dockerfile that packages a named custom kube-scheduler, so that I can run it as a container alongside the COUBES adapter for benchmarking.

#### Acceptance Criteria

1. THE SchedulerDockerfile SHALL use `registry.k8s.io/kube-scheduler:v1.33.0` as its base image.
2. THE SchedulerDockerfile SHALL copy a `KubeSchedulerConfiguration` YAML file into the image at a documented path (e.g. `/etc/kube-scheduler/scheduler-config.yaml`).
3. THE SchedulerDockerfile SHALL copy a `kubeconfig.yaml` file into the image at a documented path (e.g. `/etc/kube-scheduler/kubeconfig.yaml`).
4. THE SchedulerDockerfile SHALL set the container's default command to run `kube-scheduler` with `--config` pointing to the copied configuration file and `--secure-port=10260`.
5. THE SchedulerDockerfile SHALL include inline comments explaining each `COPY` and `CMD` instruction, so that a new contributor can understand the purpose of each file without reading external documentation.

---

### Requirement 6: Scheduler Dockerfile — Configurable Scheduling Profiles

**User Story:** As a researcher, I want the scheduler configuration to support multiple named scheduling profiles, so that I can switch between spreading and bin-packing strategies by editing a single YAML file.

#### Acceptance Criteria

1. THE default `KubeSchedulerConfiguration` file SHALL define at least two named profiles: one named `default-scheduler` using the `LeastAllocated` scoring strategy, and one named `my-scheduler` using the `MostAllocated` scoring strategy.
2. WHEN a profile uses `MostAllocated`, THE `KubeSchedulerConfiguration` SHALL configure `NodeResourcesFit` with `scoringStrategy.type: MostAllocated` and equal weights for `cpu` and `memory`.
3. WHEN a profile uses `LeastAllocated`, THE `KubeSchedulerConfiguration` SHALL configure `NodeResourcesFit` with `scoringStrategy.type: LeastAllocated` and equal weights for `cpu` and `memory`.
4. THE `KubeSchedulerConfiguration` SHALL set `leaderElection.leaderElect: false` for all profiles, as the scheduler runs as a single instance without leader election.
5. THE `KubeSchedulerConfiguration` SHALL include inline YAML comments explaining the purpose of each profile and how to add a new one.
6. THE `kubeconfig.yaml` bundled with the SchedulerDockerfile SHALL point to `http://localhost:8080` as the API server address, matching the Adapter's default port.

---

### Requirement 7: Scheduler Dockerfile — Docker Compose Integration

**User Story:** As a researcher, I want a `docker-compose.yml` that starts the custom scheduler container with the correct network configuration, so that I can bring up the full COUBES stack with a single command.

#### Acceptance Criteria

1. THE `docker-compose.yml` SHALL define a service that builds from the SchedulerDockerfile and runs the kube-scheduler container.
2. THE `docker-compose.yml` service SHALL use `network_mode: "host"` so that the scheduler container can reach the Adapter on `localhost:8080`.
3. THE `docker-compose.yml` SHALL include a comment explaining that `network_mode: "host"` is required for the scheduler to connect to the adapter running on the host.
4. THE `docker-compose.yml` SHALL set the container's verbosity flag to `--v=2` as the default log level, with a comment indicating how to increase it for debugging.
5. WHERE the user wants to override the scheduler configuration without rebuilding the image, THE `docker-compose.yml` SHALL include a commented-out `volumes` section showing how to mount a local `scheduler-config.yaml` over the image's copy.

---

### Requirement 8: Scheduler Dockerfile — Documentation

**User Story:** As a researcher, I want a README that explains how to build, configure, and run the custom scheduler, so that I can reproduce the setup on a new machine without prior knowledge of the project.

#### Acceptance Criteria

1. THE README SHALL document the prerequisites: Docker Desktop (or equivalent container runtime) and a running COUBES adapter on port 8080.
2. THE README SHALL provide the exact `docker build` and `docker run` commands needed to build and start the scheduler container.
3. THE README SHALL explain how to target a specific scheduling profile by passing `--scheduler=<profile-name>` to the Go adapter.
4. THE README SHALL explain the difference between the `default-scheduler` (spreading) and `my-scheduler` (bin-packing) profiles and when to use each.
5. THE README SHALL include a troubleshooting section covering at least: the scheduler failing to connect to the adapter, and how to increase log verbosity.
