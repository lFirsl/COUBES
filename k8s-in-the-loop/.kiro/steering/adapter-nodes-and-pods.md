# How the Adapter Manages Fake K8s Nodes and Pods

## Overview

The adapter (`misim-k8s-adapter`) maintains an in-memory store of fake Kubernetes `Node` and `Pod` objects. These are real `k8s.io/api/core/v1` types — the same structs the actual kube-scheduler expects — but they are never backed by a real cluster. The simulation owns their lifecycle; the adapter just makes them visible to K8s components via a fake API server.

---

## Node Lifecycle

### How Nodes Enter the Adapter

The simulation calls `POST /updateNodes` with a `NodeUpdateRequest`:

```go
type NodeUpdateRequest struct {
    AllNodes    v1.NodeList
    Events      []metav1.WatchEvent
    MachineSets []cluster.MachineSet  // only when cluster-autoscaler is active
    Machines    []cluster.Machine     // only when cluster-autoscaler is active
}
```

`NodeController.UpdateNodes()` stores the list and fires watch events so the kube-scheduler's informer cache is updated immediately.

When cluster-autoscaler support is enabled, `NodeController.InitMachinesNodes()` is called instead — it registers MachineSets and Machines first, then stores the nodes. This activates the autoscaler path in the adapter.

### Node Shape

Nodes are constructed with these fields populated (see `ScaleController.ScaleUpNodes`):

```go
core.Node{
    TypeMeta:   metav1.TypeMeta{APIVersion: "v1", Kind: "Node"},
    ObjectMeta: metav1.ObjectMeta{Name: machine.Name + "-node"},
    Spec:       core.NodeSpec{ProviderID: "clusterapi://" + machine.Name},
    Status: core.NodeStatus{
        Phase: "Running",
        Conditions: []core.NodeCondition{{Type: "Ready", Status: "True"}},
        Allocatable: map[core.ResourceName]resource.Quantity{
            "cpu":    cpuQuantity,
            "memory": memoryQuantity,
            "pods":   podsQuantity,
        },
        Capacity: /* same as Allocatable */,
    },
}
```

The `Ready=True` condition is essential — the kube-scheduler will not schedule onto a node that lacks it.

### Dynamic Node Scaling (Cluster-Autoscaler Path)

When the cluster-autoscaler decides to scale up, it calls `PUT /apis/cluster.x-k8s.io/v1beta1/namespaces/{ns}/machinesets/{name}/scale`. The adapter's `ScaleController.ScaleMachineSet()` handles this:

**Scale up:**
1. Creates new `cluster.Machine` objects (named `{machineSet}-machine-{id}`)
2. Calls `ScaleUpNodes()` — creates corresponding `core.Node` objects from the machine's CPU/memory/pods annotations
3. Adds them to `NodeStorage`
4. The new node is broadcast via `GetNodeUpscalingChannel()` so `PodController` can unblock and return it to the simulation

**Scale down:**
1. Finds nodes tainted with `ToBeDeletedByClusterAutoscaler`
2. Calls `ScaleDownNodes()` — removes them from `NodeStorage`
3. Calls `ScaleDownMachines()` — removes the corresponding `Machine` objects

### Node API Surface (exposed to kube-scheduler)

| Method | Path | Behaviour |
|--------|------|-----------|
| GET | `/api/v1/nodes` | Returns full `NodeList`; supports `?watch` for streaming events |
| GET | `/api/v1/nodes/{name}` | Returns single node |
| PUT | `/api/v1/nodes/{name}` | Updates a node (used by scheduler to set taints, etc.) |

---

## Pod Lifecycle

### How Pods Enter the Adapter

The simulation calls `POST /updatePods` with a `PodsUpdateRequest`:

```go
type PodsUpdateRequest struct {
    AllPods        v1.PodList          // full current pod state
    Events         []metav1.WatchEvent // ADDED/MODIFIED/DELETED events
    PodsToBePlaced v1.PodList          // subset that needs scheduling right now
}
```

`PodController.UpdatePods()` does the following:
1. Clears the `PodsToBePlaced`, `FailedPodBuffer`, and `BindedPodBuffer` buffers
2. Stores all pods and fires watch events (so the scheduler's informer sees them)
3. If `PodsToBePlaced` is non-empty, **blocks** on `PodsUpdateChannel` waiting for the scheduler to respond
4. Returns a `PodsUpdateResponse` with binding results once all pods are resolved

This blocking call is the synchronisation point between the simulation tick and the real scheduler.

### Pod Shape

Pods sent to the adapter use standard `v1.Pod` with at minimum:
- `ObjectMeta.Name` — unique name
- `Spec.Containers` with resource requests (CPU) so the scheduler can fit-check
- `Status.Phase` — `"Pending"` for pods awaiting scheduling, `"Running"` for already-placed pods

### Scheduler Binding Flow

When the kube-scheduler decides to place a pod it calls:

```
POST /api/v1/namespaces/default/pods/{podName}/binding
```

with a protobuf-encoded `v1.Binding` containing the target node name. The adapter decodes this and calls `PodController.BindPod(podName, nodeName)`:

1. Looks up the pod in storage
2. Appends a `PodScheduled=True` condition
3. Sets `pod.Spec.NodeName = nodeName` and `pod.Status.Phase = "Running"`
4. Puts a `BindingInformation{Pod, Node}` into `BindedPodBuffer`
5. Calls `updatePodChannel()` — if all pending pods are now resolved, unblocks the simulation

### Scheduler Failure Flow

When the scheduler cannot place a pod it calls:

```
PATCH /api/v1/namespaces/default/pods/{podName}/status
```

with a `v1.PodStatusResult` containing failure conditions. The adapter calls `PodController.FailedPod(podName, status)`:

1. Sets `pod.Status.Phase = "Pending"` with the failure message
2. Puts a `BindingFailureInformation{Pod, Message}` into `FailedPodBuffer`
3. Calls `updatePodChannel()` — checks if cluster-autoscaler should be triggered (see below)

### Cluster-Autoscaler Trigger

Inside `updatePodChannel()`, after all pods are processed, if:
- `FailedPodBuffer` is non-empty (some pods couldn't be scheduled), AND
- cluster-autoscaler is active (`AdapterState.IsClusterAutoscalerActive()`), AND
- upscaling is possible (`MachineSets.IsUpscalingPossible()`)

...then instead of returning immediately, the adapter **subscribes to `NodeUpscalingChannel`** and waits for the autoscaler to add a node (via the MachineSet scale endpoint). Once the new node arrives, it is added to `NewNodes` buffer and the response is sent back to the simulation.

### Pod API Surface (exposed to kube-scheduler)

| Method | Path | Behaviour |
|--------|------|-----------|
| GET | `/api/v1/pods` | Returns full `PodList`; supports `?watch` |
| GET | `/api/v1/namespaces/default/pods/{name}/status` | Returns pod status |
| PATCH | `/api/v1/namespaces/default/pods/{name}/status` | Records scheduling failure |
| POST | `/api/v1/namespaces/default/pods/{name}/binding` | Records successful binding |

---

## Watch / Streaming

The kube-scheduler uses long-lived watch connections to stay in sync. The adapter implements this via `HandleWatchableRequest` + a `BroadcastServer[metav1.WatchEvent]`:

- Every storage write (store, update, delete) publishes a `WatchEvent` to a channel
- `BroadcastServer` fans that channel out to all active watch subscribers
- HTTP responses use chunked transfer encoding and flush after each event

This means the scheduler's informer cache is updated in real-time as the simulation pushes new state.

---

## Storage Architecture

All state is held in `StorageContainer` (pure in-memory, no persistence):

```
StorageContainer
├── Pods            PodStorage       — pod list + watch broadcast + buffers
├── Nodes           NodeStorage      — node list + watch broadcast + scaling channels
├── Machines        MachineStorage   — cluster-api Machine objects
├── MachineSets     MachineSetStorage — cluster-api MachineSet objects
├── AdapterState    AdapterStateStorage — flags: autoscaler active, scaling done
├── Events          EventStorage     — K8s events (core + events.k8s.io)
├── Namespaces      NamespaceStorage
├── DaemonSets      DaemonSetStorage
├── PodIds          IdStorage        — monotonic resource version counter
└── MachineIds      IdStorage
```

`PodStorage` has a transaction mechanism (`BeginTransaction` / `EndTransaction`) to prevent race conditions when the scheduler's binding callback and the simulation's next update arrive concurrently.

---

## Unsupported Resources

Many K8s API paths are registered but return empty lists (or block forever on watch). This is intentional — the kube-scheduler queries these on startup and the adapter must respond with valid (empty) responses rather than 404s. See `UnsupportedResource()` and `GetEmptyResourceList()` in `internal/infrastructure/`.

Unsupported but stubbed: `replicasets`, `statefulsets`, `persistentvolumes`, `persistentvolumeclaims`, `services`, `storageclasses`, `csidrivers`, `csinodes`, `poddisruptionbudgets`, `jobs`, `machinedeployments`, `machinepools`.
