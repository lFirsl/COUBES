# Project Overview: Kubernetes-in-the-Loop Simulation

## What This Is

This workspace contains the reference implementation of "Kubernetes-in-the-Loop" — a framework that embeds a real Kubernetes scheduler (and optionally the cluster-autoscaler) inside a discrete-event microservice simulation. The goal is to replace hand-crafted scheduling models with the actual K8s binaries, making simulation results more realistic.

The paper describing this work: `Kubernetes-in-the-Loop - Enriching Microservice Simulation Through Authentic Container Orchestration.pdf`

## Two Repositories

### misim-orchestration (Java, Maven)
The simulation side. Built on top of MiSim 3.3.1 (a discrete-event simulator). It models microservices, pods, nodes, deployments, and autoscalers as simulation entities. When the KubeScheduler is active, it delegates scheduling decisions to the real kube-scheduler via REST.

- Entry point: `OrchestrationMain.java`
- Core model: `MiSimOrchestrationModel.java`
- Requires Java 8 (hard constraint from MiSim dependency)
- Build: `mvn clean package`

### misim-k8s-adapter (Go, module `go-kube`)
The adapter side. Implements a fake Kubernetes API server that the real kube-scheduler (or cluster-autoscaler) connects to. It stores fake nodes and pods in memory and bridges between the simulation's REST calls and the K8s component's API calls.

- Entry point: `cmd/go-kube/main.go`
- Listens on port 8000
- Build/run: `./run.sh` (start this first, before K8s components, before MiSim)

## Startup Order (Critical)
1. Start `misim-k8s-adapter` (`run.sh`)
2. Start Kubernetes components (kube-scheduler, cluster-autoscaler) with `--leader-elect=false`
3. Start `misim-orchestration`

## Key Design Principle

The adapter acts as a **fake etcd + API server** from the perspective of the K8s scheduler. The simulation pushes node/pod state into the adapter via its own REST endpoints (`/updateNodes`, `/updatePods`). The K8s scheduler reads that state via standard K8s API calls (`/api/v1/nodes`, `/api/v1/pods` with watch). When the scheduler makes a binding decision, the adapter captures it and returns it to the simulation.
