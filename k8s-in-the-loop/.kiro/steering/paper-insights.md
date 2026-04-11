# Paper Insights: Kubernetes-in-the-Loop

Source: *Kubernetes-in-the-Loop: Enriching Microservice Simulation Through Authentic Container Orchestration* — Straesser et al., EAI VALUETOOLS 2023.

---

## Core Motivation

Existing microservice simulators model orchestration mechanisms (scheduling, autoscaling) by hand. Hand-crafted models cannot represent the full complexity and configuration space of real K8s components, and they go stale as K8s is updated. The paper's answer: don't model them — run the real binaries and wire them into the simulation.

---

## General Concept: Connecting a DES with an Event-Driven System

The paper formalises the integration pattern, which is useful to understand when building a similar system:

- Define a subset **Θ ⊆ E** of simulation events that are relevant to the external system (K8s). Only these events cross the boundary.
- A **transformation τ** maps each simulation event `e ∈ Θ` to one or more K8s API events `e' ∈ E'`.
- The **simulation state S(t)** at the time of the event must also be sent, because the K8s component needs full context (e.g., all currently running pods) to make a decision.
- The K8s component runs its black-box logic and returns a response `r ∈ E'`.
- The response is translated back and applied to simulation entities.

This is the abstract pattern behind the `updatePods` / `updateNodes` REST calls.

---

## Black-Box Information Repository (BIR)

K8s uses entities that have no equivalent in the simulation (node labels, pod affinities, MachineSet definitions, taint/toleration rules). The paper calls these **M_E** — entities that exist in the EDS but not the DES.

The solution is a **Black-Box Information Repository**: YAML files provided by the user at simulation start. The simulation stores them opaquely and forwards them to the adapter on relevant events. The K8s component reads them normally. This is how:

- Node labels and pod affinity rules are passed to the kube-scheduler
- MachineSet definitions are passed to the cluster-autoscaler

In the implementation, these are the Kubernetes YAML files in the `orchestrationDir` config directory.

---

## What Θ Includes (and Excludes)

Events forwarded to K8s (Θ):
- Any event that creates, modifies, or deletes a **container/pod**
- Any event that creates, modifies, or deletes a **node**

Events excluded from Θ (stay inside the simulation):
- CPU scheduling events
- Logging events
- Load balancer decisions
- Circuit breaker / retry events

---

## Adapter Role

The adapter acts as the **kube-apiserver** from the K8s components' perspective. Because all K8s control-plane communication goes through the kube-apiserver, a single adapter is sufficient for any number of K8s components. The adapter:

1. Implements the relevant subset of the Kubernetes API (pods, nodes, namespaces, Cluster API for MachineSets/Machines)
2. Translates simulation state into K8s watch events (ADDED/MODIFIED/DELETED)
3. Captures binding decisions from the kube-scheduler and scaling decisions from the cluster-autoscaler
4. Returns translated responses to the simulation

---

## Scheduling Integration Details

- The kube-scheduler is configured via a **profile YAML** at startup (standard K8s config). No code changes needed to switch policies.
- Three policies demonstrated: `default` (least-allocated), `most-allocated`, `europe-only` (node affinity).
- Pod affinity/node affinity rules are specified in standard K8s Deployment YAML files stored in the BIR.
- The scheduler processes pods **one at a time** in order — the order matters for placement when resources are equal across nodes. The paper fixed deployment order to make experiments repeatable.
- To break ties between equally-suitable nodes, the paper pre-placed dummy containers on nodes with slightly different CPU reservations (0.05, 0.1, 0.15, 0.2 cores).

---

## Cluster-Autoscaler Integration Details

- Uses the **Kubernetes Cluster API** (open-source, provider-agnostic) rather than a cloud-provider-specific implementation. This avoids vendor lock-in in the adapter.
- The CA subscribes to pod and node events. The adapter forwards all kube-scheduler decisions directly to the CA.
- A **MachineSet** defines a group of machines with equal resources, scalable between a min and max count.
- Two expansion policies compared: `random` (randomly picks a node group) and `least-waste` (prefers the node group that wastes fewest CPU cores after placement).
- `least-waste` always prefers the smaller machine type, leading to more scale-up events but lower cost. `random` gives more stable capacity growth.
- Known limitation: node start times are not modelled — CA responses are applied immediately in the simulation. The paper notes this as future work.

---

## Overhead Measurements

From the scheduling experiment (5 nodes, TeaStore with 7 microservices, 20 req/s for 5 minutes):
- Total simulation run: ~9.2 seconds
- Time spent on adapter + kube-scheduler communication: ~10 milliseconds

The overhead is negligible. The adapter is not a performance bottleneck.

---

## Startup Sequence and Initialisation

1. Adapter starts first, initialises all resource caches with **empty lists** (not null — K8s components expect valid empty responses on startup).
2. K8s components start and perform their initial list-then-watch requests against the adapter.
3. Simulation starts and immediately pushes the initial node list to the adapter via `updateNodes`.
4. The kube-scheduler's informer cache is populated; it is now ready to schedule.

---

## Limitations Acknowledged in the Paper

- Component responses are applied **immediately** in simulation time (no startup delay modelled for new nodes).
- Simulation accuracy depends on MiSim's performance model calibration — payload size variability and parametric dependencies are not well-modelled in MiSim core.
- Integrating a new K8s component requires identifying and implementing its required API endpoints in the adapter — needs K8s API expertise.
- The Kubernetes Leases API is not implemented, so components using leader election must be started with `--leader-elect=false`.

---

## Use Cases Identified

1. **Microservice application designers** — evaluate realistic deployment scenarios early in development, before having a real cluster.
2. **Performance engineers** — what-if analysis with different workloads and K8s configurations.
3. **Container orchestration researchers** — design and test new orchestration policies against realistic application models.

---

## Key Design Decisions Worth Reusing

- Use a **single adapter** that mimics the kube-apiserver — all K8s components talk to one place.
- Use **real K8s API types** (`k8s.io/api/core/v1`) in the adapter, not custom structs — zero translation needed on the K8s side.
- Use **chunked HTTP streaming** for watch connections — matches the real K8s list-then-watch protocol exactly.
- Use **protobuf decoding** for binding requests (the kube-scheduler sends bindings as protobuf, not JSON).
- Store K8s-specific config (YAML files) in a BIR and forward them opaquely — avoids having to model K8s-specific entities in the simulation.
- Disable leader election (`--leader-elect=false`) since the Leases API is not implemented.
