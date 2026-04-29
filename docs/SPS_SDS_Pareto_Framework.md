# COUBES Scoring Framework: SDS, SPS, and Pareto Analysis

## 1. Introduction

The COUBES scoring framework provides a structured methodology for evaluating and comparing container orchestration schedulers across multiple metrics. Rather than relying on a single composite score, the framework decomposes scheduler evaluation into two orthogonal dimensions — **decision quality** and **scheduling performance** — and applies Pareto analysis to identify optimal tradeoffs between them.

The framework is designed to be:
- **Reproducible:** Default parameters are fixed, ensuring consistent results across independent evaluations.
- **Scenario-independent:** The same methodology applies to any COUBES test scenario, with only the theoretical bounds varying per scenario.
- **Extensible:** New metrics can be incorporated into either dimension without restructuring the framework.

---

## 2. Metric Categories

Scheduler metrics are classified into two categories based on what they depend on.

### 2.1 Decision-Based Metrics

Decision-based metrics depend solely on the scheduler's placement choices. Given the same simulated infrastructure and workload, these metrics are deterministic and independent of the hardware on which the scheduler itself executes. They answer the question: *how good are the scheduling decisions?*

Current decision-based metrics:
- **Power Efficiency** — Total energy consumption (Wh) of the simulated datacenter, derived from host power models and VM utilisation over simulated time.
- **Bin-Packing Efficiency** — Time-weighted consolidation ratio (active cloudlets / active VMs), measuring how effectively the scheduler consolidates workloads onto fewer resources.
- **Simulated Time-to-Completion (TTC)** — Total simulated time (seconds) required to complete all submitted cloudlets.

### 2.2 Performance-Based Metrics

Performance-based metrics depend on both the scheduler's logic and the hardware on which it executes. They measure the operational cost of obtaining a scheduling decision. They answer the question: *how fast does the scheduler produce decisions?*

Current performance-based metrics:
- **Scheduling Throughput** — Pods scheduled per wall-clock second.
- **Scheduling Latency** — Wall-clock time (ms) between pod submission and binding decision.

---

## 3. Normalisation

All metrics are transformed so that higher values indicate better performance. Decision-based metrics are normalised to the interval [0, 1]. Performance-based metrics are expressed as unbounded ratios against a baseline, where 1.0 indicates parity. The transformation method differs by category.

### 3.1 Decision-Based Metrics: Min-Max Normalisation

Each decision-based metric is normalised against scenario-specific theoretical bounds:

$$\text{Norm}(x) = \frac{x - x_{\min}}{x_{\max} - x_{\min}}$$

where $x_{\min}$ and $x_{\max}$ represent the best and worst theoretically achievable values for the metric given the scenario's fixed infrastructure (host count, PE count, power model, cloudlet lengths, etc.). These bounds are deterministic properties of the scenario and do not change when new schedulers are evaluated.

For lower-is-better metrics (energy, TTC), the normalised value is inverted:

$$\text{Norm}_{\text{inv}}(x) = 1 - \text{Norm}(x)$$

This ensures that all normalised decision-based metrics follow the convention that higher values indicate better outcomes.

### 3.2 Performance-Based Metrics: Baseline-Relative Ratio

Performance-based metrics are expressed as a ratio against a declared baseline scheduler evaluated on the same hardware:

$$R = \frac{P_{\text{scheduler}}}{P_{\text{baseline}}}$$

For lower-is-better metrics (latency), the ratio is inverted so that improvement yields $R > 1$:

$$R = \frac{P_{\text{baseline}}}{P_{\text{scheduler}}}$$

The ratio is used directly without further transformation. $R = 1.0$ indicates parity with the baseline, $R = 1.3$ indicates a 30% improvement, and $R = 0.7$ indicates a 30% degradation. This preserves the true magnitude of performance differences between schedulers without introducing arbitrary bounding parameters.

---

## 4. Composite Sub-Scores

### 4.1 Scheduler Decision Score (SDS)

The SDS is the equally-weighted arithmetic mean of all normalised decision-based metrics:

$$\text{SDS} = \frac{1}{|D|} \sum_{i \in D} \text{Norm}_i$$

where $D$ is the set of decision-based metrics.

### 4.2 Scheduler Performance Score (SPS)

The SPS is the equally-weighted arithmetic mean of all performance-based metric ratios:

$$\text{SPS} = \frac{1}{|P|} \sum_{j \in P} R_j$$

where $P$ is the set of performance-based metrics and $R_j$ is the baseline-relative ratio for metric $j$. An SPS of 1.0 indicates aggregate parity with the baseline scheduler.

### 4.3 Overall Scheduler Score (OSS)

The OSS is the equally-weighted arithmetic mean of the two sub-scores:

$$\text{OSS} = \frac{\text{SDS} + \text{SPS}}{2}$$

---

## 5. Pareto Analysis

The OSS provides a single composite ranking, but it obscures tradeoffs between decision quality and scheduling performance. Pareto analysis addresses this by treating SDS and SPS as independent objectives.

### 5.1 Dominance

Scheduler $A$ **dominates** scheduler $B$ if:

$$\text{SDS}_A \geq \text{SDS}_B \quad \text{and} \quad \text{SPS}_A \geq \text{SPS}_B$$

with at least one strict inequality. A dominated scheduler is objectively inferior — there exists another scheduler that is at least as good on both dimensions and strictly better on one.

### 5.2 Pareto Frontier

The Pareto frontier is the set of all non-dominated schedulers. These represent the best available options: improving one dimension necessarily requires sacrificing the other. Any scheduler not on the frontier can be discarded without loss.

### 5.3 Dimensionality Considerations

Pareto analysis is most effective with a small number of objectives. As the number of objectives increases, the probability that any scheduler dominates another decreases, and the frontier converges towards the full set of candidates. This phenomenon — the curse of dimensionality in multi-objective optimisation — is why the framework reduces metrics to two composite sub-scores (SDS, SPS) before applying Pareto analysis, rather than operating on individual metrics directly.

The individual metrics remain available for drill-down analysis (Section 6) but are not used as Pareto objectives.

---

## 6. Interpretation and Drill-Down

The evaluation procedure is as follows:

1. **Compute SDS and SPS** for each scheduler under evaluation.
2. **Identify the Pareto frontier** to eliminate dominated schedulers.
3. **Apply minimum thresholds** if applicable (e.g., "SPS must exceed 0.35") to filter the frontier based on hard constraints.
4. **Rank remaining schedulers by OSS** to produce a final recommendation.
5. **Decompose sub-scores into individual metrics** to explain the result.

Step 5 is critical for actionable interpretation. A scheduler on the Pareto frontier with SDS = 0.83 may achieve that score through strong energy efficiency (0.91) but weaker bin-packing (0.72). Similarly, an SPS of 0.55 may reflect acceptable throughput (0.70) constrained by high latency (0.40). The sub-score decomposition reveals which specific metrics drive the composite result and where improvement opportunities exist.

### 6.1 Tradeoff Quantification

When two frontier schedulers exhibit a tradeoff (one has higher SDS, the other higher SPS), the tradeoff can be quantified as:

$$\text{Tradeoff}_{A \to B} = \frac{\Delta\text{SDS}}{\Delta\text{SPS}} = \frac{\text{SDS}_B - \text{SDS}_A}{\text{SPS}_A - \text{SPS}_B}$$

A tradeoff ratio of 2.0 indicates that each 1% decrease in SPS corresponds to a 2% increase in SDS. This ratio is only meaningful when a tradeoff exists (i.e., the two schedulers differ in sign across the two dimensions). When one scheduler dominates another on both dimensions, it is reported as a strict improvement and no tradeoff ratio is computed.

---

## 7. Computational Complexity

Let $n$ denote the number of schedulers and $m$ the number of individual metrics.

| Step | Complexity |
|---|---|
| Normalisation | $O(n \cdot m)$ |
| Sub-score computation | $O(n \cdot m)$ |
| Pareto frontier (on SDS, SPS) | $O(n^2)$ |
| Threshold filtering | $O(n)$ |
| OSS ranking | $O(n \log n)$ |

The overall complexity is $O(n^2 + n \cdot m)$, dominated by the pairwise dominance check. For the expected scale of COUBES evaluations ($n \leq 10$, $m \leq 30$), computation is negligible.

---

## 8. Custom Weight Overrides

The default framework uses equal weights at all levels: within SDS, within SPS, and between SDS and SPS. This ensures that the standard COUBES score is deterministic and comparable across independent evaluations.

However, specific evaluation contexts may warrant non-equal weighting. The framework supports weight overrides at two independent levels:

### 8.1 Within Sub-Scores

Individual metric weights within SDS or SPS can be adjusted to reflect scenario-specific priorities:

$$\text{SDS}_w = \frac{\sum_{i \in D} w_i \cdot \text{Norm}_i}{\sum_{i \in D} w_i}$$

$$\text{SPS}_w = \frac{\sum_{j \in P} w_j \cdot R_j}{\sum_{j \in P} w_j}$$

For example, an evaluation focused on energy-constrained environments may assign higher weight to power efficiency within SDS.

### 8.2 Between Sub-Scores

The relative importance of decision quality versus scheduling performance can be adjusted:

$$\text{OSS}_w = \frac{w_{\text{SDS}} \cdot \text{SDS} + w_{\text{SPS}} \cdot \text{SPS}}{w_{\text{SDS}} + w_{\text{SPS}}}$$

For example, an evaluation of batch workloads where scheduling speed is less critical may assign higher weight to SDS.

### 8.3 Reporting Convention

When custom weights are used, the standard equal-weight OSS, SDS, and SPS must also be reported alongside the custom-weighted variants. This preserves comparability with other evaluations while allowing the custom weighting to inform the specific analysis.
