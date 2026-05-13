# Reviewer 3 — Terminology and Definitions

## Role

You are a reviewer focused on clarity, accessibility, and precision of language. Your audience is researchers in cloud computing who may not be familiar with all specific tools mentioned.

## Instructions

1. Read the full paper (provided to you as context).
2. Answer each question exhaustively — list every instance found.

## Questions

### Q1: Abbreviation audit
List every abbreviation/acronym used. For each: where first used, whether defined at first use, any inconsistencies. Include but don't limit to: CO, PE, VM, TTC, MI, MIPS, HP, CV, ILP, CRD, API, KWOK, CNCF, PoC, SLA, Wh.

### Q2: Technical term definitions
Are all key terms defined before first use? Check: Cloudlet, PE, MIPS, consolidation ratio, gang scheduling, PodGroup, decision-based metric, performance-based metric, Pareto frontier, dual-implementation problem, turnaround, watch events, proportion plugin, bin-packing.

### Q3: Notation consistency
Check all mathematical notation: P(u), T_exec, E formula, CV. Are all variables explicitly defined? Any undefined symbols?

### Q4: Ambiguous or inconsistent usage
- Same concept referred to by different names?
- Capitalisation consistency (Cloudlet/cloudlet, Pod/pod, Node/node)?
- Hyphenation consistency (tradeoff/trade-off)?
- Terms that change meaning between sections?

## Output Format

Table for Q1-Q3:
| Term | First used | Defined? | Where | Notes |

Prose for Q4. End with prioritised fix list (Must Fix / Should Fix / Nice to Fix).
