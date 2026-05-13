# Paper Review Process — Steering Runbook

## Overview

This document describes how to run the 8 reviewer agents against the GECON paper. Each agent has a specific focus area and reads from an instruction file in `.kiro/knowledge/reviewer_agents/`.

## Paper Location

- **Abstract**: `/home/flori/COUBES/GECON_Paper/samplepaper.tex` (lines 62–70)
- **Section 1**: `/home/flori/COUBES/GECON_Paper/Sections/chapter_1_introduction.tex`
- **Section 2**: `/home/flori/COUBES/GECON_Paper/Sections/chapter_2_background.tex`
- **Section 3**: `/home/flori/COUBES/GECON_Paper/Sections/chapter_3_architecture.tex`
- **Sections 4–7**: `/home/flori/COUBES/GECON_Paper/Sections/chapter_4_evaluation_core.tex`

## Reviewer Agents

| # | Name | Focus | Instruction File |
|---|---|---|---|
| 1 | Prior Feedback Audit | Were CLOSER reviewer concerns addressed? | `reviewer_agents/agent_1_prior_feedback.md` |
| 2 | Maths & Results | Formulas, numerical consistency, statistics | `reviewer_agents/agent_2_maths_results.md` |
| 3 | Terminology | Abbreviations, definitions, notation | `reviewer_agents/agent_3_terminology.md` |
| 4 | General Review | Full PC-member review (novelty, soundness, recommendation) | `reviewer_agents/agent_4_general_review.md` |
| 5 | Structure & Flow | Transitions, argument coherence, redundancy | `reviewer_agents/agent_5_structure_flow.md` |
| 6 | Devil's Advocate | Adversarial attacks on weakest claims | `reviewer_agents/agent_6_devils_advocate.md` |
| 7 | GECON Economics | Economic framing and venue fit | `reviewer_agents/agent_7_gecon_economics.md` |
| 8 | Presentation | Writing quality, formatting, LNCS compliance | `reviewer_agents/agent_8_presentation.md` |

## How to Run

### Invocation

All 8 agents run in parallel via a subagent pipeline. Each agent receives:
1. Its instruction file content (role + questions)
2. The full paper text (all sections concatenated)

### Output Location

Outputs are written to a timestamped subfolder in `debug/`:

```
debug/review_YYYY-MM-DD-HH/
├── agent_1_output.md
├── agent_2_output.md
├── ...
└── agent_8_output.md
```

The `debug/` folder is gitignored, so review outputs don't pollute the repository.

### Running the Review

Ask: "Run all the reviewers" or "Start up the reviewer agents."

The process:
1. Read all paper sections into a single text block.
2. Read each agent's instruction file from `.kiro/knowledge/reviewer_agents/`.
3. Create timestamped output directory in `debug/`.
4. Launch all 8 agents in parallel (subagent pipeline), each receiving its instructions + paper text.
5. Write each agent's output to its respective file.
6. Report a cross-reviewer summary highlighting consensus issues.

### After the Review

Common follow-up actions:
- "Fix the terminology issues" — apply agent 3's recommendations
- "Address the economics framing" — apply agent 7's suggestions
- "What are the top issues across all reviewers?" — synthesise consensus
- "Run just agent 4 again" — re-run a single reviewer after changes

## When to Run

- After any significant paper revision (structural changes, new sections, rewritten paragraphs)
- Before submission deadlines
- After addressing a batch of reviewer feedback (to verify fixes didn't introduce new issues)

## Notes

- Agent 8 (Presentation) previously compared against the K8s-in-the-Loop paper PDF. This is now simplified to standalone formatting review. The comparison can be re-added by referencing `/home/flori/COUBES/Research_Papers/Kubernetes-in-the-Loop - Enriching Microservice Simulation Through Authentic Container Orchestration.pdf` in the prompt.
- The prior feedback in Agent 1 is from the CLOSER 2026 submission. Update if the paper receives new external reviews.
