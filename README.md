# COUBES

**COUBES** (Container Orchestration Universal Benchmark for Evaluating Schedulers) is a framework that integrates [CloudSim 7G](https://github.com/Cloudslab/cloudsim) with lightweight Kubernetes clusters to enable reproducible, multi-metric benchmarking of container orchestration schedulers.

This repository contains the current **proof-of-concept implementation**, developed as part of an MSci Project (2024–2025). It will transition towards a more mature and extensible version at a later date.

## Overview

The aim of COUBES is to provide a universal testing harness for Kubernetes schedulers using CloudSim. However, instead of re-implementing the scheduling logic inside CloudSim, COUBES delegates scheduling decisions to a live Kubernetes scheduler via an adapter layer.

- A custom CloudSim broker, [Live_Kubernetes_Broker_EX](https://github.com/lFirsl/COUBES/blob/master/src/main/java/org/example/kubernetes_broker/Live_Kubernetes_Broker_Ex.java) extends the default `DatacentreBrokerEX`.
- The broker forwards CloudSim resources (VMs and Cloudlets) to an adapter written in Go. This can be found in the [k8s-cloudsim-adapter](https://github.com/lFirsl/COUBES/tree/master/k8s-cloudsim-adapter) folder.
- The adapter translates these into Kubernetes equivalents (Nodes and Pods), using [KWOK](https://kwok.sigs.k8s.io/) for lightweight cluster emulation.
- The native Kubernetes scheduler performs scheduling as usual.
- Results are returned from KWOK to the adapter, then mapped back into CloudSim’s resource model so the simulation can proceed.


## Current Status
Last update: 29/08/2025

- Currently supports basic scenarios with the Kubernetes Default Scheduler.
- Implements a scoring system for comparing schedulers across multiple metrics.
- Evaluated using three test scenarios: undercrowding, fragmentation, and performance vs efficiency. These can be found in the [Test Suite Folder](https://github.com/lFirsl/COUBES/tree/master/src/main/java/org/example/testSuite).

Future development will focus on:
- Broader test scenarios and additional metrics (e.g. latency, throughput).
- Support for metric-aware schedulers.
- Scalability evaluation with larger simulated clusters.
- Integration with other orchestration frameworks beyond Kubernetes.


## Structure
- [src/main/java/org/example](src/main/java/org/example) - Main folder for CloudSim simulations and the
[Live_Kubernetes_Broker_EX](https://github.com/lFirsl/COUBES/blob/master/src/main/java/org/example/kubernetes_broker/Live_Kubernetes_Broker_Ex.java) custom class that implements communication to the middleware.
- [k8s-cloudsim-adapter](https://github.com/lFirsl/COUBES/tree/master/k8s-cloudsim-adapter) - The middleware/adapter that faclitates communications between CloudSim and Kubernetes.
- [second-scheduler](https://github.com/lFirsl/COUBES/tree/master/second-scheduler) - Docker files used to spin-up an instance of the Kubernetes Default Scheduler using a `Most Allocated` behaviour, which performs basic bin-packing (instead of the default `Least Allocated`, which puts new pods onto the least busy node).

## How to run

You'll need [Go](https://go.dev/doc/install), [Docker Desktop](https://www.docker.com/products/docker-desktop/) (for running Container Orchestration Clusters), [KWOK](https://kwok.sigs.k8s.io/docs/user/installation/) and [Java JDK21](https://www.oracle.com/java/technologies/downloads/#java21) installed.

You should also clone [CloudSim 7.0.1](https://github.com/Cloudslab/cloudsim/releases/tag/7.0.1) on your local machine and compile it for use using the `mvn clean install -DskipTests` command within the CloudSim 7.0.1 repository.

1. Start up [Docker Desktop](https://www.docker.com/products/docker-desktop/) .
2. From the CLI, prepare the KWOK cluster using `kwokctl create cluster` then `kubectl cluster-info --context kwok-kwok`.
3. Within the [k8s-cloudsim-adapter](https://github.com/lFirsl/COUBES/tree/master/k8s-cloudsim-adapter) folder, run `go run main.go` to start the middleware/adapter.
4. Build and Run any of the tests within [src/main/java/org/example/testSuite](https://github.com/lFirsl/COUBES/tree/master/src/main/java/org/example/testSuite).

The test should successfully run, with a list of the outcomes of all Cloudlets and some basic metrics being displayed at the end.
## Figures
### Ideal Design
This diagram showcases the ideal design that this repository aims to follow:
![](images/Ambitious_Design.png)

### Proof-of-Concept Prototype Implementation
This diagram showcases the implementation design of the Proof-of-Concept Prototype:
![](images/Implementation_Design.png)

