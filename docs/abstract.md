# COUBES — Abstract

Evaluating container orchestration schedulers remains challenging due to the absence of a standardised, reproducible, and framework-agnostic benchmarking methodology. Existing approaches either rely on costly and irreproducible real-cluster experiments or require reimplementing schedulers within simulators, limiting comparability and fidelity.

This paper introduces COUBES (Container Orchestration Universal Benchmark for Evaluating Schedulers), a scheduler-in-the-loop simulation framework designed to enable fair, multi-metric, and repeatable evaluation of native schedulers without dual implementation.

COUBES integrates discrete-event simulation with a lightweight simulated Kubernetes control plane, supports normalised multi-metric scoring, and is extensible across container orchestration frameworks and schedulers.

We present a proof-of-concept implementation combining CloudSim 7G with a middleware layer that implements a simulated Kubernetes API server, allowing unmodified scheduler binaries — including the default kube-scheduler and Volcano — to connect directly and make scheduling decisions against simulated infrastructure, eliminating the need for a real or emulated cluster.

Through targeted evaluation scenarios, the prototype demonstrates that COUBES can reliably distinguish between contrasting scheduling strategies and surface expected performance–efficiency trade-offs.

These results establish COUBES as a viable and extensible foundation for standardised scheduler benchmarking and position it as a practical contender to existing evaluation methodologies.

---

# COUBES — Abstract (v2: deliverables emphasis)

Evaluating container orchestration schedulers remains challenging due to the absence of a standardised, reproducible, and framework-agnostic benchmarking methodology. Existing approaches either rely on costly and irreproducible real-cluster experiments or require reimplementing schedulers within simulators, limiting comparability and fidelity.

This paper presents three contributions to address this gap.

First, we introduce **COUBES** (Container Orchestration Universal Benchmark for Evaluating Schedulers), a scheduler-in-the-loop simulation framework that enables fair, repeatable evaluation of native, unmodified schedulers. COUBES combines discrete-event simulation with a simulated Kubernetes API server, allowing real scheduler binaries — including the default kube-scheduler and Volcano — to connect directly and make scheduling decisions against simulated infrastructure, eliminating the need for a real or emulated cluster.

Second, we present a **suite of targeted evaluation scenarios** designed to isolate specific scheduling behaviours — including bin-packing under fragmentation, performance–efficiency trade-offs, and workload undercrowding — providing a reusable test harness for comparing schedulers under controlled conditions.

Third, we propose a **dual-axis scoring framework** comprising a Scheduler Decision Score (SDS) for placement quality (energy consumption, bin-packing efficiency, time-to-completion) and a Scheduler Performance Score (SPS) for scheduling speed (throughput, latency), compared via Pareto frontier analysis to identify non-dominated schedulers and quantify decision-quality versus performance tradeoffs.

Through evaluation of the proof-of-concept prototype, we demonstrate that COUBES can reliably distinguish between contrasting scheduling strategies and surface expected trade-offs. These results establish COUBES as a viable and extensible foundation for standardised scheduler benchmarking.