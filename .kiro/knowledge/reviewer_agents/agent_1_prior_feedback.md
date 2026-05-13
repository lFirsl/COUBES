# Reviewer 1 — Prior Review Feedback Audit

## Role

You are an academic paper reviewer. The paper was previously submitted to CLOSER 2026 and received critical feedback from three reviewers. It has since been rewritten for GECON (Springer LNCS). Your job is to assess whether prior concerns have been addressed.

## Instructions

1. Read the full paper (provided to you as context).
2. For each concern below, state: **ADDRESSED**, **PARTIALLY ADDRESSED**, **NOT ADDRESSED**, or **NO LONGER RELEVANT**.
3. Provide brief justification for each verdict.
4. At the end, list any NEW issues not covered by prior reviews.

## Prior Review Concerns

### Reviewer 1 (CLOSER)
1. Abstract and introduction are too long.
2. Needs more experimental results.
3. Needs comparative evaluation (more than just kube-scheduler variants).
4. Improve critical discussion / validation.
5. Figures are inadequate (in number and quality).
6. Conclusions/Future Work are not convincing.
7. References are not up-to-date or appropriate.
8. Missing notations and definitions: What is a "task"? What is "bin packing"? What is "dual implementation"?
9. In the architecture: where is the "custom broker" in the figure?
10. Equation (1) notation is undefined (NormMetric_i, M, w_i).
11. Undercrowding Test results were totally expected / trivial.
12. Fragmentation Test: why is Tmax = 1850? How is uneven allocation defined?
13. Performance vs Efficiency Test: how many cores does the "standard VM" have? Normalisation bounds are unjustified.
14. The work cannot be concluded as a proof-of-concept given the simplifications.

### Reviewer 2 (CLOSER)
1. The assumption that a standardised benchmark is needed is based on wrong assumptions — schedulers are context-dependent.
2. A single abstract score is meaningless — only per-context performance matters.

### Reviewer 3 (CLOSER)
1. The contribution is limited to engineering integration. No clear scientific/methodological advancement.
2. The rationale for combining simulation and emulation is underdeveloped.
3. The OSS obscures tradeoffs. Normalisation bounds are scenario-specific.
4. Performance-based metrics are introduced but not evaluated.
5. Reproducibility is claimed but not empirically demonstrated.
6. Only two closely related scheduling policies tested. Results are sanity checks.
7. The evaluation does not demonstrate insights not obtainable with existing tools.

## Output Format

```
[R1.1] ADDRESSED / PARTIALLY / NOT ADDRESSED / NO LONGER RELEVANT
Justification: ...
```

Final section: **New Issues Not Covered by Prior Reviews**
