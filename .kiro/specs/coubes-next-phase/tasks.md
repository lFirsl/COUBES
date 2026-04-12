# Implementation Plan: coubes-next-phase

## Overview

Two independent features implemented in Go and YAML/Docker:
1. Adapter Test Mode — round-robin scheduling without kube-scheduler
2. Scheduler Dockerfile — multi-profile KubeSchedulerConfiguration with docs

---

## Tasks

- [x] 1. Implement `TestModeScheduler` in `scheduler/test_mode_scheduler.go`
  - Create `scheduler/test_mode_scheduler.go` with `TestModeScheduler` struct
  - Implement `Schedule(pods []communicator.CsPod, nodes []communicator.CsNode) BatchDecision`
  - Sort nodes lexicographically by name; assign pod `i` to `sortedNodes[i % M]`
  - Set `BindingTimestamp = time.Now()` for each assignment
  - When `len(nodes) == 0`, return all pods as `Unschedulable` with reason `"no nodes available"`
  - Reuse existing `PodAssignment`, `PodFailure`, `BatchDecision` types from the same package
  - Reuse existing `extractID` helper from `scheduler.go`
  - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5, 4.1, 4.2, 4.3, 4.4, 4.5_

  - [x] 1.1 Write property test — Property 1: Assignment completeness and uniqueness
    - File: `scheduler/test_mode_scheduler_test.go`
    - Generators: `rapid.IntRange(0, 50)` for N, `rapid.IntRange(1, 20)` for M
    - Assert `len(Scheduled) + len(Unschedulable) == N`
    - Assert no duplicate pod IDs across both lists
    - Tag: `// Feature: coubes-next-phase, Property 1: Assignment completeness and uniqueness`
    - **Validates: Requirements 4.1**

  - [x] 1.2 Write property test — Property 2: Round-robin formula correctness
    - File: `scheduler/test_mode_scheduler_test.go`
    - For each `PodAssignment`, assert `NodeID == extractID(sortedNodes[podIndex % M])`
    - Run scheduler twice on same input, assert outputs are identical
    - Tag: `// Feature: coubes-next-phase, Property 2: Round-robin formula correctness`
    - **Validates: Requirements 2.2, 4.3**

  - [x] 1.3 Write property test — Property 3: Balance invariant
    - File: `scheduler/test_mode_scheduler_test.go`
    - Count assignments per node; assert each count is `floor(N/M)` or `ceil(N/M)`
    - Tag: `// Feature: coubes-next-phase, Property 3: Balance invariant`
    - **Validates: Requirements 4.2**

  - [x] 1.4 Write property test — Property 4: BindingTimestamp presence
    - File: `scheduler/test_mode_scheduler_test.go`
    - Assert every `PodAssignment.BindingTimestamp.IsZero() == false`
    - Tag: `// Feature: coubes-next-phase, Property 4: BindingTimestamp presence`
    - **Validates: Requirements 2.4**

  - [x] 1.5 Write unit tests for `TestModeScheduler`
    - N=0 pods → empty `BatchDecision`
    - M=0 nodes → all pods in `Unschedulable` with reason `"no nodes available"`
    - N=1, M=1 → single assignment to the only node
    - N=5, M=3 → exact node indices `[0,1,2,0,1]`
    - _Requirements: 2.1, 2.2, 2.3_

- [x] 2. Extend `Communicator` with test mode fields and `HandleUpdateState`
  - Add `testMode bool` and `testSched *scheduler.TestModeScheduler` fields to `Communicator` struct
  - Update `NewCommunicator` signature to accept `testMode bool`; initialise `testSched` when `testMode == true`
  - Branch `HandleSchedulePods`: in test mode call `c.testSched.Schedule(pods, csNodes)` synchronously; in normal mode keep existing `round.Begin` / `round.Wait` path
  - Add `HandleUpdateState` handler for `POST /pods/update-state`:
    - Decode request body containing the completed pod ID
    - Delete the pod from `c.store` and `c.pods`
    - In test mode: collect pending pods (pods with no `nodeName`), call `TestModeScheduler.Schedule`, return `BatchDecision`
    - In normal mode: return HTTP 200 with empty `BatchDecision` (no external scheduler interaction needed)
    - Return HTTP 404 if pod ID not found
  - _Requirements: 1.4, 2.6, 3.1, 3.2, 3.3, 3.5_

  - [x] 2.1 Write property test — Property 5: Update-state rescheduling in test mode
    - File: `communicator_test/communicator_test.go` or integration test
    - Generate pending pods and nodes; call `HandleUpdateState` via `httptest`
    - Assert returned `BatchDecision` satisfies round-robin formula (same as Property 2)
    - Tag: `// Feature: coubes-next-phase, Property 5: Update-state rescheduling in test mode`
    - **Validates: Requirements 3.3**

- [x] 3. Update `main.go` with `--test-mode` flag and conditional route registration
  - Add `--test-mode` boolean flag via `flag.Bool`
  - Pass `*testMode` to `NewCommunicator`
  - Register `POST /pods/update-state` → `comm.HandleUpdateState` unconditionally
  - Wrap all fake Kubernetes API route registrations (`/api/v1/...`, `/apis/...`) in `if !*testMode { ... }`
  - Log `[TEST MODE] Adapter running in standalone test mode. No kube-scheduler connection will be made.` when `*testMode == true`
  - When `*testMode == true` and `*kubeconfig != ""`, log a notice and continue (no error)
  - _Requirements: 1.1, 1.2, 1.3, 1.5, 3.6_

- [x] 4. Checkpoint — verify test mode end-to-end
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. Update `second-scheduler/my-scheduler.yaml` to two-profile configuration
  - Replace existing single-profile config with a two-profile `KubeSchedulerConfiguration`
  - Profile 1: `schedulerName: default-scheduler`, `NodeResourcesFit` with `LeastAllocated`, equal cpu/memory weights
  - Profile 2: `schedulerName: my-scheduler`, `NodeResourcesFit` with `MostAllocated`, equal cpu/memory weights
  - Both profiles: disable all score plugins except `NodeResourcesFit`
  - Set `leaderElection.leaderElect: false` globally
  - Add inline YAML comments explaining each profile and how to add a new one
  - _Requirements: 6.1, 6.2, 6.3, 6.4, 6.5_

- [x] 6. Update `second-scheduler/Dockerfile` with inline comments
  - Keep base image `registry.k8s.io/kube-scheduler:v1.33.0`
  - Keep `COPY` instructions for `my-scheduler.yaml` → `/etc/kube-scheduler/scheduler-config.yaml` and `kubeconfig.yaml` → `/etc/kube-scheduler/kubeconfig.yaml`
  - Remove cert `COPY` instructions that are no longer needed (adapter uses HTTP, not HTTPS)
  - Set CMD to `kube-scheduler --config=/etc/kube-scheduler/scheduler-config.yaml --secure-port=10260`
  - Add inline comment on each `COPY` and `CMD` instruction explaining its purpose
  - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_

- [x] 7. Update `second-scheduler/docker-compose.yml`
  - Set `network_mode: "host"` on the scheduler service
  - Add comment explaining that `network_mode: host` is required for the scheduler to reach the adapter on `localhost:8080`
  - Set verbosity flag `--v=2` as default; add comment indicating how to increase it for debugging
  - Add commented-out `volumes` section showing how to mount a local `scheduler-config.yaml` over the image copy
  - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [x] 8. Rewrite `second-scheduler/README.md`
  - Prerequisites section: Docker Desktop (or equivalent) and a running COUBES adapter on port 8080
  - Build and run commands: exact `docker build` and `docker run` / `docker compose up` commands
  - Profile selection guide: how to target `default-scheduler` (spreading) vs `my-scheduler` (bin-packing) via `--scheduler` flag on the Go adapter
  - Explain the difference between LeastAllocated and MostAllocated and when to use each
  - Troubleshooting section: scheduler failing to connect to adapter; how to increase log verbosity
  - _Requirements: 8.1, 8.2, 8.3, 8.4, 8.5_

- [x] 9. Write property test — Property 6: Reset empties store
  - File: `store/store_test.go`
  - Generate arbitrary nodes and pods, add them to the store
  - Call `store.Reset()`
  - Assert `store.GetNodes()` and `store.GetPods()` both return empty slices
  - Tag: `// Feature: coubes-next-phase, Property 6: Reset empties store`
  - **Validates: Requirements 3.4**

- [x] 10. Final checkpoint — Ensure all tests pass
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. Fix protobuf binding compilation error and test real kube-scheduler integration
  - ✓ Compilation error resolved (tooling issue fixed)
  - ✓ Protobuf binding parser working correctly
  - ✓ Tested with real kube-scheduler (v1.33.0)
  - ✓ All 10 pods successfully scheduled via protobuf bindings
  - ✓ CloudSim test completed successfully (160.01 simulated time, 71.55 Wh energy)
  - ✓ No scheduler errors in logs
  - Simple string parsing approach successfully extracts node names from protobuf body
  - Adapter logs show: "Parsed protobuf binding: pod=cspod-X -> node=csnode-Y"
  - _Requirements: Real kube-scheduler integration, protobuf binding support_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Property tests use `pgregory.net/rapid` (already a dependency); place corpus files under `testdata/rapid/`
- `cloudsim-7.0/` and `k8s-in-the-loop/` are read-only — do not touch
- `second-scheduler/` is updated in-place; no new folder is created
- **All tasks complete** - both test mode and real kube-scheduler integration working
