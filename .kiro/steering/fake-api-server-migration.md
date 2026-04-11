---
inclusion: manual
---

# Evaluation: Migrating COUBES to a Fake API Server Approach

## The Problem with the Current KWOK Emulation Approach

COUBES currently requires a full KWOK cluster (etcd, kube-apiserver, kube-controller-manager, kube-scheduler) running in Docker before any simulation can start. The adapter is a client of this cluster. This creates several concrete problems:

1. **Polling latency**: `HandleBatchPods` polls `AreAllPodsScheduled()` every second for up to 30 seconds. `DeletePodAndWaitForRescheduling` polls every 250ms for up to 30 attempts. These delays are real wall-clock time added to every simulation step.

2. **External dependency fragility**: If Docker Desktop isn't running, or the KWOK cluster isn't started, or the kubeconfig is stale, the simulation fails immediately with a panic in `NewKubeClient`.

3. **State leakage between runs**: The cluster persists between simulation runs. `sendResetRequestToControlPlane()` must be called manually at the end of each run, and if it's skipped (e.g. due to a crash), the next run starts with stale pods/nodes.

4. **No autoscaler support**: The current adapter has no path for the cluster-autoscaler because it doesn't control the cluster's node lifecycle — KWOK does.

5. **Scheduler switching requires cluster reconfiguration**: To test a different scheduler, you need to either reconfigure the KWOK cluster or run a second scheduler container (see `second-scheduler/`), which adds more infrastructure.

---

## What the Fake API Server Approach Would Look Like

Instead of the adapter being a client of a KWOK cluster, the adapter becomes the API server that the kube-scheduler connects to. The adapter holds all state in memory and speaks the K8s API protocol directly.

### New Architecture

```
CloudSim (Java)
    │  HTTP (custom REST: /nodes, /schedule-pods, /pods/update-state)
    ▼
k8s-cloudsim-adapter (Go) ← now acts as fake kube-apiserver
    │  real K8s API (watch, list, binding, status patch)
    ▼
kube-scheduler binary (unmodified, --master localhost:8080)
    │  binding decision (protobuf POST /binding)
    ▼
adapter captures binding, unblocks simulation
    │  HTTP response
    ▼
CloudSim binds cloudlet to VM
```

The KWOK cluster, Docker Desktop, etcd, and kube-controller-manager are all eliminated.

---

## What Needs to Be Implemented in the Adapter

The k8s-in-the-loop project has already solved this problem. The required K8s API surface for the kube-scheduler is well-documented in `misim-k8s-adapter/pkg/interfaces/router.go`. The minimum set is:

### Required endpoints (scheduler reads these)
| Endpoint | Purpose |
|---|---|
| `GET /api/v1/nodes?watch=true` | Scheduler's node informer cache |
| `GET /api/v1/pods?watch=true` | Scheduler's pod informer cache |
| `GET /api/v1/nodes/{name}` | Individual node lookup |
| `GET /api/v1/namespaces` | Namespace list (startup) |
| `GET /apis/apps/v1/daemonsets?watch=true` | DaemonSet informer (can return empty) |

### Required endpoints (scheduler writes these)
| Endpoint | Purpose |
|---|---|
| `POST /api/v1/namespaces/default/pods/{name}/binding` | Scheduling decision (protobuf) |
| `PATCH /api/v1/namespaces/default/pods/{name}/status` | Scheduling failure |

### Stubbed endpoints (return empty, scheduler queries on startup)
`/api/v1/services`, `/api/v1/persistentvolumes`, `/apis/apps/v1/replicasets`, `/apis/apps/v1/statefulsets`, `/apis/storage.k8s.io/v1/storageclasses`, `/apis/storage.k8s.io/v1/csidrivers`, `/apis/storage.k8s.io/v1/csinodes`, `/apis/policy/v1/poddisruptionbudgets`, `/apis/batch/v1/jobs`

### Critical implementation details
- **Watch streams**: Must use chunked HTTP transfer encoding and flush after each event. The scheduler's informer uses long-lived watch connections.
- **Protobuf binding**: The kube-scheduler sends binding requests as protobuf, not JSON. Must use `k8s.io/apimachinery/pkg/runtime/serializer/protobuf` to decode.
- **Resource versions**: Every stored object needs a monotonically increasing `ResourceVersion`. The scheduler uses this to detect stale watch connections.
- **Empty lists on startup**: All stubbed endpoints must return valid empty lists (e.g. `{"kind":"NodeList","items":[]}`) — not 404s. The scheduler will crash on 404 during its initial list phase.
- **Leader election**: Start the scheduler with `--leader-elect=false`. The Leases API is complex to implement and not needed for single-scheduler scenarios.

---

## Migration Plan

### Phase 1: In-memory storage layer
Replace `kube_client/` with an in-memory store for nodes and pods. The store must support:
- `StoreNode(node)` / `GetNodes()` / `DeleteNode(name)`
- `StorePod(pod)` / `GetPods()` / `DeletePod(name)` / `UpdatePod(name, pod)`
- Watch broadcast channels (one per resource type)
- Monotonic resource version counter

This is essentially the `pkg/storage/` package from misim-k8s-adapter, adapted for COUBES's needs.

### Phase 2: K8s API server routes
Add the K8s API routes to `main.go` alongside the existing simulation routes. The simulation routes (`/nodes`, `/schedule-pods`, `/pods/update-state`) stay unchanged — they are the CloudSim-facing API.

The new K8s-facing routes serve the in-memory store to the scheduler.

### Phase 3: Replace polling with blocking channel
The current `HandleBatchPods` polls `AreAllPodsScheduled()` in a loop. Replace this with a channel-based approach:

```go
// When all pods are submitted, block on a channel
podUpdateCh := store.InitPodUpdateChannel(len(newPods))

// The binding handler unblocks the channel when all pods are resolved
// POST /api/v1/namespaces/default/pods/{name}/binding
//   → store.BindPod(name, nodeName)
//   → if allPodsResolved: podUpdateCh <- response

result := <-podUpdateCh
```

This eliminates all polling delays. The response arrives the instant the scheduler makes its last binding decision.

### Phase 4: Replace KWOK node creation with in-memory node creation
`SendFakeNodeFromCs` currently calls `kc.SendNode(node)` which creates a node in the KWOK cluster. Replace this with `store.StoreNode(node)` and broadcast a `ADDED` watch event. The scheduler's node informer will pick it up immediately.

### Phase 5: Remove KWOK dependencies
Remove `kube_client/` entirely. Remove the `--kubeconfig` flag. Remove the KWOK-specific labels, taints, and tolerations from node/pod specs (they were only needed to target KWOK nodes).

The scheduler now connects to the adapter directly: `kube-scheduler --master http://localhost:8080 --leader-elect=false`.

---

## What Changes for the CloudSim Side

Nothing. The Java broker (`Live_Kubernetes_Broker_Ex`) calls the same HTTP endpoints:
- `POST /nodes` — still syncs nodes
- `POST /schedule-pods` — still submits pods and blocks for results
- `POST /pods/update-state` — still deletes a pod and waits for rescheduling
- `DELETE /reset` — still resets all state

The only difference is that the adapter no longer needs a running KWOK cluster. The simulation can start immediately after `go run main.go`.

---

## What Changes for Running Tests

**Before (current)**:
```bash
# Terminal 1
docker desktop start
kwokctl create cluster
# Terminal 2
cd k8s-cloudsim-adapter && go run main.go
# Terminal 3
mvn exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test"
```

**After (fake API server)**:
```bash
# Terminal 1
cd k8s-cloudsim-adapter && go run main.go
# Terminal 2 (one-time setup, or bundled in a script)
./kube-scheduler --master http://localhost:8080 --leader-elect=false
# Terminal 3
mvn exec:java -Dexec.mainClass="org.example.testSuite.Fragmentation_Test"
```

Docker Desktop and KWOK are no longer required. The kube-scheduler binary is the only external dependency.

---

## Risks and Mitigations

| Risk | Mitigation |
|---|---|
| Protobuf binding decoding is non-trivial | Copy the decoding logic from misim-k8s-adapter verbatim — it's already solved |
| Watch stream implementation is complex | Use the `BroadcastServer` pattern from misim-k8s-adapter |
| Scheduler version compatibility | Pin to a specific kube-scheduler version; test against it |
| Race conditions in binding handler | Use a mutex + transaction pattern (as in misim-k8s-adapter's `BeginTransaction/EndTransaction`) |
| Rescheduling detection is harder without KWOK | The channel-based approach makes this explicit: failed pods go into a failure buffer, and the simulation can retry |

---

## Effort Estimate

| Component | Effort | Notes |
|---|---|---|
| In-memory storage layer | Medium | Can be adapted from misim-k8s-adapter's `pkg/storage/` |
| K8s API routes (list + watch) | Medium | Router pattern is clear from misim-k8s-adapter |
| Protobuf binding handler | Low | Copy from misim-k8s-adapter |
| Channel-based scheduling sync | Medium | Replaces polling loop |
| Remove KWOK client code | Low | Delete `kube_client/`, update `main.go` |
| Update node/pod specs (remove KWOK-specific fields) | Low | Remove taints, tolerations, KWOK labels |
| Testing | High | Need to validate scheduling correctness without KWOK as ground truth |

Total: approximately 2–3 weeks of focused development for a working prototype.

---

## Recommendation

The migration is strongly recommended. The fake API server approach is architecturally cleaner, eliminates all external infrastructure dependencies, removes polling latency, and opens the door to cluster-autoscaler integration. The misim-k8s-adapter provides a complete reference implementation that can be adapted rather than built from scratch.

The simulation-facing API (`/nodes`, `/schedule-pods`, `/pods/update-state`) should remain unchanged to preserve backward compatibility with existing test scenarios and the Java broker.
