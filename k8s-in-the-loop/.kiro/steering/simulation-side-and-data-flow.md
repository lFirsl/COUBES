# Simulation Side & End-to-End Data Flow

## misim-orchestration Architecture

The simulation is a discrete-event model built on DESMO-J (via MiSim 3.3.1). Time advances in ticks; at each tick the `ManagementPlane` singleton drives three concerns:

1. `checkForScaling()` — each `Deployment` evaluates its `AutoScaler` and adjusts `desiredReplicaCount`
2. `maintainDeployments()` — each `Deployment` reconciles current vs desired replica count, creating or removing `Pod` objects
3. `checkForPendingPods()` — each registered `Scheduler` drains its waiting queue

### Key Simulation Entities

```
MiSimOrchestrationModel (extends MiSimModel)
└── ManagementPlane (singleton)
    ├── Cluster
    │   ├── List<Node>          — simulation nodes (track CPU reservation)
    │   └── delayMap            — optional network delay config
    ├── List<Deployment>        — one per microservice group
    │   ├── ReplicaSet (Set<Pod>)
    │   └── AutoScaler          — HPA / SimpleReactive / Fake
    └── Map<SchedulerType, Scheduler>
        ├── DefaultScheduler    — simple bin-packing, no external call
        └── KubeScheduler       — delegates to real kube-scheduler via adapter
```

### Node (simulation side)

`cambio.simulator.orchestration.entities.kubernetes.Node` is a DESMO-J `NamedEntity`. It tracks:
- `totalCPU` — capacity in cores
- `reserved` — currently allocated CPU (sum of running pods' demands)
- `List<Pod> pods` — pods placed on this node
- `nodeIpAddress` — auto-assigned from `192.168.49.x` range
- `kubernetesRepresentation` — the `V1Node` object used when talking to the adapter

`addPod(pod)` checks `reserved + pod.cpuDemand <= totalCPU` before accepting. On success it fires a `StartPodEvent` immediately.

`startRemovingPod(pod)` transitions the pod to `TERMINATING` and schedules a `CheckPodRemovableEvent` that polls until the pod's containers have zero work demand, then calls `removePod()`.

### Pod (simulation side)

`cambio.simulator.orchestration.entities.kubernetes.Pod` tracks:
- `PodState` — `PENDING → RUNNING → TERMINATING → SUCCEEDED / FAILED`
- `Set<Container>` — each container wraps a `MicroserviceInstance`
- `cpuDemand` — cores requested (used for fit-checking on nodes)
- `owner` — the `Deployment` that created this pod
- `kubernetesRepresentation` — the `V1Pod` sent to the adapter

State transitions drive container lifecycle: `RUNNING` starts all containers, `TERMINATING` calls `startShutdown()` on each microservice instance, `SUCCEEDED` terminates containers, `FAILED` kills them.

---

## KubeScheduler — The Bridge

`KubeScheduler` is the only scheduler that crosses the process boundary. It maintains two internal caches that mirror what the real kube-scheduler knows:

- `internalRunningPods` — pods the scheduler has already seen as Running
- `internalPendingPods` — pods the scheduler has already seen as Pending

On each `schedulePods()` call:

1. Compute delta events — pods removed from nodes since last call become `DELETED` watch events; pending pods no longer in the queue become `DELETED` events
2. Build `v1PodList` (all currently placed pods as Running) and `podsToBePlaced` (new pods from the waiting queue as Pending)
3. If no events and no new pods → skip (scheduler would do nothing anyway)
4. POST to `/updatePods` on the adapter — **this call blocks** until the adapter returns binding results
5. Handle `SchedulerResponse`:
   - For each `BindingInformation`: call `candidateNode.addPod(pod)` on the simulation node, call `pod.bindToNode(nodeName)`, move pod from `internalPendingPods` to `internalRunningPods`
   - For each `BindingFailureInformation`: put pod back in `podWaitingQueue`
   - For each `newNode` (autoscaler added one): call `KubernetesParser.createNodeFromKubernetesObject()` and add to `Cluster`
   - For each `deletedNode`: remove from `Cluster`

### Node Initialisation

In `KubeScheduler`'s constructor, it immediately calls `KubeSchedulerController.updateNodes()` to push the initial node list to the adapter. If the adapter isn't running yet, this throws an `IOException` and the scheduler logs that it is unsupported for this run (graceful degradation).

---

## REST Contract Between Simulation and Adapter

All calls go to `http://127.0.0.1:8000/` (hardcoded in `KubeSchedulerController`).

### POST /updateNodes

**Request** (`UpdateNodesRequest`):
```json
{
  "nodes": [ /* V1Node list */ ],
  "events": [ /* V1WatchEvent list */ ],
  "machineSets": [ /* optional, for autoscaler */ ],
  "machines":    [ /* optional, for autoscaler */ ]
}
```

**Response** (`NodeUpdateResponse`): echoes back the node list. Simulation ignores the body.

### POST /updatePods

**Request** (`UpdatePodsRequest`):
```json
{
  "allPods":       [ /* all V1Pod objects */ ],
  "events":        [ /* V1WatchEvent list */ ],
  "podsToBePlaced": [ /* V1Pod objects needing scheduling */ ]
}
```

**Response** (`SchedulerResponse`):
```json
{
  "binded":       [ { "pod": "name", "node": "name" } ],
  "failed":       [ { "pod": "name", "message": "reason" } ],
  "newNodes":     [ /* V1Node list — autoscaler added these */ ],
  "deletedNodes": [ /* V1Node list — autoscaler removed these */ ]
}
```

The simulation blocks on this call until the adapter unblocks (i.e., all `podsToBePlaced` have been either bound or failed by the real scheduler).

---

## Object Conversion (KubeObjectConverter)

`KubeObjectConverter` (in `misim-orchestration`) translates between simulation entities and Kubernetes API objects:

- `convertNodes(List<Node>)` → `UpdateNodesRequest` with `V1Node` objects built from simulation node names and CPU capacity
- `convertPod(Pod, phase)` → `V1Pod` with containers, resource requests, and the given phase string
- `createPodAddedEvent(pod, phase)` → `V1WatchEvent{type: "ADDED", object: V1Pod}`
- `createPodDeletedEvent(pod, phase)` → `V1WatchEvent{type: "DELETED", object: V1Pod}`

---

## Autoscaler Integration

### Simulation-side scalers (no adapter involvement)
- `HorizontalPodAutoscaler` — scales based on CPU utilisation target
- `SimpleReactiveAutoscaler` — scales based on upper/lower utilisation bounds with a hold time
- `FakeAutoscaler` — deterministic increment/decrement for testing

These adjust `Deployment.desiredReplicaCount` only. The actual pod creation/deletion happens in `maintainDeployments()`.

### Real cluster-autoscaler (via adapter)
When `useClusterAutoscaler: true` in the orchestration config:
1. Simulation sends `MachineSets` and `Machines` with the initial `updateNodes` call
2. The adapter activates the autoscaler path and exposes the Cluster API endpoints
3. When pods fail to schedule, the adapter waits for the cluster-autoscaler to call the MachineSet scale endpoint
4. The adapter creates new nodes and returns them in `newNodes` of the `PodsUpdateResponse`
5. The simulation adds those nodes to its `Cluster` and the scheduler retries

---

## Configuration

The orchestration config is a YAML file passed at startup. Key fields relevant to nodes/pods/scheduling:

```yaml
orchestrate: true
orchestrationDir: ./k8s-configs   # directory with K8s YAML manifests

nodes:
  amount: 3
  cpu: 4.0                        # cores per node (used when importNodes: false)

importNodes: false                 # if true, reads node specs from orchestrationDir
useClusterAutoscaler: false        # enables the autoscaler path

schedulerPrio:
  - name: KUBE
    prio: 1

scaler:
  importScaler: false
  scalerList:
    - service: my-service
      scalerType: HPA
      targetUtilization: 0.7
      minReplicas: 1
      maxReplicas: 5
```
