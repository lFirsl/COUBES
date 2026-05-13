# GECON Paper — Steering Guide

## Overview

**Target venue:** GECON (Economics of Grids, Clouds, Systems, and Services) — Springer LNCS format.
**Page count:** 19 pages (as of 2026-05-09).
**Location:** `/home/flori/COUBES/GECON_Paper/`
**Compilation:** `cd /home/flori/COUBES/GECON_Paper && latexmk -pdf -interaction=nonstopmode samplepaper.tex`
**Always recompile after every revision.**

---

## File Structure

| File | Content |
|---|---|
| `samplepaper.tex` | Main file: preamble, title, authors, abstract, \input{} calls, credits, bibliography |
| `Sections/chapter_1_introduction.tex` | Section 1: Introduction |
| `Sections/chapter_2_background.tex` | Section 2: Background and Related Work |
| `Sections/chapter_3_architecture.tex` | Section 3: Architecture |
| `Sections/chapter_4_evaluation_core.tex` | Sections 4–6: Testing Methodology, Results & Discussion, Conclusion |
| `myrefs.bib` | BibTeX references |
| `Images/` | Figures (PNG format) |

---

## Section Summaries

### Section 1: Introduction (`chapter_1_introduction.tex`)
- Motivates the problem: no standardised scheduler benchmarking methodology
- Illustrates the problem with a researcher scenario
- Introduces COUBES as the solution
- States 3 contributions: (1) hybrid simulation–emulation design, (2) PoC prototype with fake API server, (3) validation through targeted experiments
- Roadmap paragraph at the end

### Section 2: Background and Related Work (`chapter_2_background.tex`)
- **Table 1:** Key metrics for CO benchmarking (performance, resource efficiency, QoS)
- **Table 2:** Comparison of existing K8s benchmarking tools (ClusterLoader2, kube-burner, CloudSim, Kubemark, KWOK, COFFEE, COUBES)
- **§ Metrics Usage for Benchmarking** — fragmentation of metric selection across studies
- **§ Benchmarking Container Orchestration Systems** — fidelity vs speed/reproducibility tradeoff
- **§ Cloud Simulation for Scheduler Evaluation** — CloudSim (dual-implementation problem), MiSim (same problem in microservices domain)
- **§ Scheduler-in-the-Loop Simulation** — k8s-in-the-loop validates the fake API server pattern; COUBES builds on it for benchmarking
- **§ Gaps and motivation** — 5 gaps identified; k8s-in-the-loop solves (v) but not (i)–(iv); COUBES addresses all

### Section 3: Architecture (`chapter_3_architecture.tex`)
- **§ Design Objectives** — native schedulers, reproducibility (decision=cross-machine, performance=same machine), non-determinism handled via repeated runs, multi-metric, extensibility, lightweight
- **§ Cloudlets and Their Analogy to Kubernetes Pods** — mapping between CloudSim and K8s concepts
- **§ System Overview** — 4 components (CloudSim, Middleware, Scheduler Binary, Metrics Engine); 6-step workflow
- **§ Architectural Components:**
  - CloudSim Extensions (custom broker, PowerDatacenter, enriched Cloudlets with gang/queue/affinity; gang holding mechanism explained)
  - Middleware Layer (fake API server with simulation-facing + scheduler-facing interfaces)
  - Scheduler Binary (unmodified, connects to middleware, no cluster infra needed)
- **§ Metrics and Comparison** — decision-based vs performance-based distinction; Pareto analysis on raw metrics; SDS/SPS mentioned as future work for aggregation

### Section 4: Testing Methodology (`chapter_4_evaluation_core.tex`, lines 1–57)
- **PoC Prototype** — Go middleware as fake K8s API server, supports kube-scheduler + Volcano
- **Evaluation objective** — demonstrate COUBES is viable for surfacing scheduler tradeoffs
- **The Schedulers** — 3 configs: Least Allocated, Most Allocated, Volcano (proportion + gang + nodeorder)
- **The Test Suite** — Fragmentation, Sustained Overload, Gang Atomicity
- **Specifications** — standard VM (4 PEs, 250 MIPS, P(u)=150+350u), standard Cloudlet (40000 MI, 1 PE, 160s). Defines PE, priority queues (HP/batch), individual pods, gangs.
- **Determinism** — COUBES itself is 0% variance (50 runs test mode). Schedulers introduce variance via random tie-breaking: kube CV≤9%, Volcano CV≤2.5%. Results use median of 50 runs.

### Section 4 (cont.): Test Scenarios (lines 58–180)
Each test follows: description → Setup (inline table) → Expected behaviour → Results and analysis (table + prose)

- **Fragmentation Test** — 5 VMs × 5 PEs, 2 waves. Spreading avoids fragmentation (best TTC), packing saves energy. Volcano = Most Allocated when no gang/queue engaged.
- **Sustained Overload Test** — 5 homogeneous VMs, 71 pods, 3 waves, mixed HP/batch + gangs. Least≈Most on homogeneous nodes. Volcano slower (gang holding) but better HP turnaround (proportion plugin) and better power efficiency per unit time.
- **Gang Atomicity Test** — 3 VMs × 4 PEs, 4 interleaved gangs. kube-scheduler deadlocks (partial placement), Volcano succeeds (atomic placement).

### Section 5: Results and Discussion (lines 183–240)
- Categorises 4 tradeoff types surfaced: quantitative (energy vs TTC), null distinction (Least≈Most on homogeneous), qualitative (works vs doesn't), priority differentiation (HP vs batch turnaround)
- Validates COUBES can distinguish, identify equivalence, surface both quantitative and qualitative tradeoffs, compare different paradigms
- Gap matrix table (Table 4) showing COUBES addresses all criteria
- **Limitations** — CPU+memory only, K8s only tested, limited test suite, no multi-tenancy

### Section 6: Conclusion (lines 245–end)
- Summary: COUBES resolves dual-implementation problem via fake API server middleware
- Key result: surfaces meaningful differences including qualitative correctness guarantees
- Future work: richer resources, cross-framework, ILP bounds, expanded test suite

---

## Key Citations

| Key | What |
|---|---|
| `CloudSim` | CloudSim simulator |
| `MiSim` | MiSim microservice simulator |
| `k8sintheloop` | Kubernetes-in-the-Loop (Straesser et al. 2023) — validates scheduler-in-the-loop pattern |
| `volcano` | Volcano batch scheduler (GitHub) |
| `straesser2023systematicCOFFEE` | COFFEE benchmarking framework |
| `kwok_github` | KWOK lightweight emulation |
| `kubemark` | Kubemark |
| `clusterloader2` | ClusterLoader2 |
| `kube-burner` | kube-burner |

**Rule:** Never write citations yourself. Ask the user to source official ones.

---

## Formatting Notes

- LNCS format (`llncs.cls`), Springer proceedings
- Max 4 heading levels: `\section`, `\subsection`, `\subsubsection`, `\paragraph`
- Tables: caption above (`\caption` before `\begin{tabular}`)
- Figures: caption below
- Credits section required at end (Acknowledgments + Disclosure of Interests)
- Author/affiliation info is still placeholder — needs updating before submission
- Keywords in abstract are still placeholder

---

## Test Results (Pass 3 — canonical)

All tests use 30% static power (P(u) = 150 + 350u, 500W max).

| Test | Metric | Least Alloc. | Most Alloc. | Volcano |
|---|---|---|---|---|
| Fragmentation | TTC (s) | 1651 | 1762 | 1762 |
| Fragmentation | Energy (Wh) | 701.27 | 576.99 | 576.99 |
| Fragmentation | Consolidation | 1.26 | 1.85 | 1.85 |
| Overload | TTC (s) | 973 | 973 | 1094 |
| Overload | Energy (Wh) | 545.92 | 546.98 | 571.79 |
| Overload | HP turnaround (s) | 370.9 | 370.8 | 328.3 |
| Overload | Batch turnaround (s) | 638.7 | 638.5 | 667.4 |
| Gang Atomicity | Completed | 0/12 | 0/12 | 12/12 |
| Gang Atomicity | TTC (s) | deadlock | deadlock | 242 |

Results stored in: `cloudsim-experimental/results/{fragmentation_pass_3,overload_homogeneous_pass_2,gang_atomicity_pass_2}/`

---

## TODO / Incomplete Items

- [ ] Author names, affiliations, ORCIDs — still placeholder
- [x] Keywords — done (container orchestration, scheduler benchmarking, cost-benefit analysis, energy efficiency, discrete-event simulation, Kubernetes)
- [ ] Credits section — still template text
- [ ] Figure 1 (`Images/Ambitious Design.png`) — removed from paper for now. Add updated architecture diagram if space permits.
- [ ] `\titlerunning{}` — may need abbreviated title for running header
- [ ] Overload test power model in code uses `P(u)=50+450u` with static=30% which gives 150W idle — matches paper. But the formula written in the Overload test's code comment says `static=0.30` which is correct.
- [ ] Version numbers — need to state versions used: kube-scheduler v1.33.0, Volcano v1.10.0, CloudSim 7.0.1, Go version, Java 21
- [ ] Pareto analysis figure — apply Pareto dominance to the 3 tests' results with a visual (graph/plot). Currently the comparison method is described in §3 but not demonstrated on the paper's own data. Prose alone is hard to follow; a figure would make it concrete. Consider for camera-ready if space permits.

## Pending Decisions

- [x] **Soften "universal" language?** Resolved: title changed to "Towards Universal Benchmarking of Container Orchestration Schedulers". Keeps the ambition, "Towards" signals it's a direction.
- [x] **Add scalability discussion?** Done — added as subsection in evaluation with data from 3 scale points.
- [x] **Compute SDS/SPS values** — done, see `docs/SDS_SPS_Pass3_Computation.md`. Decision: NOT included in paper due to space constraints. Scoring section now describes Pareto on raw metrics as primary, SDS/SPS as future work.
- [ ] **Version numbers** — need to state versions used: kube-scheduler v1.33.0, Volcano v1.10.0, CloudSim 7.0.1, Go version, Java 21. Add to the PoC paragraph or a footnote.

## Scalability Data (2026-05-10)

Collected across three scale points, all homogeneous (standard VMs, 1-PE pods, 10 waves):

| Scale | Nodes | Pods | Test mode | Volcano | kube-scheduler |
|---|---|---|---|---|---|
| Base | 50 | 5,000 | ~1s | 42s | 701s |
| 2× | 100 | 10,000 | 2s | 66s | >60 min (killed) |
| 20× | 1,000 | 100,000 | 15s | — | — |

**Key findings:**
- COUBES framework overhead: ~170k pods/s regardless of scale (test mode)
- Bottleneck is the scheduler, not COUBES
- Volcano 16.7× faster than kube-scheduler at 5k pods (grows with scale)
- Reason: kube-scheduler individually rejects each unschedulable pod (~10ms each); Volcano silently skips them (fixed 5s stall per round)
- Decision-based metrics identical across all schedulers at same scale

**Parallelisation note:** Multiple test scenarios can run in parallel (independent adapter+scheduler per test) for decision-based metrics. Performance-based metrics require sequential execution to avoid resource contention on the host machine. This reinforces the decision/performance metric split.

Results stored in:
- `results/scalability_pass_1/` (50 nodes, kube + Volcano)
- `results/scalability_large_pass_1/` (100 nodes, Volcano + kube incomplete)
- Test files: `Scalability_Test.java`, `Scalability_Test_Large.java`, `Scalability_Test_XL.java`

## Variance Analysis (2026-05-10)

Quantified run-to-run variance from scheduler non-determinism (random tie-breaking in
`selectHost()` reservoir sampling, Go auto-seeded PRNG).

**COUBES itself is deterministic:** test mode produces 0% variance over 5 runs.

| Scenario | Scheduler | TTC CV | Energy CV | n |
|---|---|---|---|---|
| Test mode | built-in | 0% | 0% | 5 |
| Overload heterogeneous | kube-scheduler | 6.37% | 1.12% | 50 |
| Overload heterogeneous | Volcano | 2.46% | 0.88% | 50 |
| Overload homogeneous | kube-scheduler | 9.02% | 1.72% | 50 |
| Overload homogeneous | Volcano | 0.02% | 0.98% | 50 |
| Max variance (pathological) | kube-scheduler | 61.59% | 26.54% | 100 |
| Max variance (pathological) | Volcano | 69.67% | 28.03% | 100 |

**Key findings:**
- Variance is scheduler-induced, not framework-induced (proven by test mode = 0%)
- Energy is always low-variance (<2% CV) regardless of scenario
- Volcano is more deterministic than kube-scheduler on realistic tests (gang/queue logic imposes fixed ordering)
- Volcano is near-deterministic on homogeneous nodes (0.02% CV)
- kube-scheduler has *higher* variance on homogeneous nodes (9%) than heterogeneous (6.4%) — every decision is a tie-break
- Pathological variance requires intentionally adversarial setups (few pods, heterogeneous nodes, identical starting scores)
- For paper results: report median over multiple runs

**Parallel runner:** `run_parallel.sh` — starts N adapters + N scheduler containers upfront,
runs Java processes in parallel batches. Requires `--port` flag on adapter and `-Dadapter.port`
system property in Java broker (both implemented). Each scheduler container needs unique
`--secure-port` to avoid bind conflicts on host network.

Results stored in: `results/variance_analysis/`
