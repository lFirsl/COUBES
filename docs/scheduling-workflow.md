# Scheduling Workflow

## Overview

CloudSim delegates cloudlet-to-VM scheduling to the adapter, which either runs a built-in
scheduler (test mode) or forwards decisions to a real kube-scheduler (full mode). The
workflow supports multiple rescheduling rounds: when cloudlets complete and free capacity,
pending cloudlets are retried.

## The Scheduling Loop

### Round 1: Initial submission

1. **CloudSim broker** creates VMs on hosts, then sends them to the adapter via `POST /nodes`
2. Broker calls `POST /schedule` with a snapshot:
   - `nodes`: the VMs (each becomes a fake K8s node)
   - `pods`: the cloudlets to schedule (each becomes a fake K8s pod)
   - `completedPodIds`: empty (nothing finished yet)

3. **Adapter** processes the snapshot:
   - Syncs nodes in the in-memory store (diff-based add/remove)
   - Creates pods in the store, broadcasting ADDED watch events

4. **Scheduling** (mode-dependent):
   - *Test mode*: `TestModeScheduler.Schedule()` runs resource-aware round-robin,
     tracking free PEs per node. Pods that can't fit anywhere are returned as unschedulable.
   - *Full mode*: kube-scheduler sees new pods via its watch stream, runs
     filter → score → bind for each. Bindings arrive via `POST /binding` (protobuf),
     failures via `PATCH /status`. The adapter's `SchedulingRound` counts decisions
     until all pods are resolved.

5. **Adapter returns** a `BatchDecision`: scheduled pods (with node assignments) and
   unschedulable pods.

6. **Broker processes** the decision:
   - Scheduled → `sendNow(CLOUDLET_SUBMIT)` to start execution on the assigned VM
   - Unschedulable → stay in `cloudletsSubmittedToMiddle` for retry later

### Wave 2: More cloudlets arrive while wave 1 runs

If delayed cloudlets arrive (e.g., at t=50), the broker calls `POST /schedule` with just
the new pods. Previously unschedulable pods are **not** re-sent — no capacity has changed,
so there's no point. The scheduler evaluates only the new pods.

### Rescheduling: Cloudlets complete, capacity frees up

When a cloudlet finishes in CloudSim:

1. `processCloudletReturn` adds the cloudlet ID to `completedSinceLastRound`
2. A `RESCHEDULE_PENDING` event is scheduled at +1 simulated second (batches completions)
3. When the event fires, the broker calls `POST /schedule` with:
   - `pods`: all pending cloudlets (new + previously unschedulable)
   - `completedPodIds`: IDs of cloudlets that just finished

4. **Adapter** deletes completed pods from the store → DELETED watch events
5. In full mode, the scheduler sees "pods deleted = CPU freed" → flushes its backoff
   queue, moving unschedulable pods to the active queue for immediate re-evaluation
6. Adapter creates pending pods in the store → ADDED watch events
7. New scheduling round begins

The cycle repeats until all cloudlets complete.

### Key invariant

The broker only re-sends unschedulable pods when completions have happened
(`completedSinceLastRound` is non-empty). This ensures the scheduler's backoff queue is
flushed naturally by the completed pod DELETE events, without artificial interference.

## Test Mode vs Full Mode

| Aspect | Test Mode | Full Mode |
|---|---|---|
| Scheduler | Built-in round-robin | Real kube-scheduler (Docker) |
| Resource-aware | Yes (tracks free PEs per node) | Yes (kube-scheduler's NodeResourcesFit) |
| Dependencies | None | Docker + scheduler container |
| Deterministic | Yes (lexicographic node order) | No (scheduler is async) |
| Latency | <1ms per round | ~5-20ms per round (scheduler evaluation) |
| Backoff queue | N/A | Handled via pod DELETE events |

## Adapter Endpoints

| Endpoint | Method | Purpose |
|---|---|---|
| `/nodes` | POST | Sync CloudSim VMs → adapter nodes (diff-based) |
| `/schedule` | POST | Submit SimulationSnapshot; returns BatchDecision |
| `/schedule-pods` | POST | Legacy: submit pods only; returns BatchDecision |
| `/pods/update-state` | POST | Delete finished pod; return rescheduling decisions |
| `/reset` | DELETE | Clear all state, reset scheduling round |
| `/pods/{id}/status` | GET | Get in-memory pod status by CloudSim ID |

## Scheduler Backoff Queue (Full Mode)

When the kube-scheduler declares a pod unschedulable, it enters an internal backoff queue
(1s initial, 10s max). The scheduler won't re-evaluate the pod until either:

- The backoff timer expires, or
- A cluster event triggers re-evaluation (e.g., a pod deletion frees CPU)

COUBES relies on the second mechanism: when completed pods are deleted from the store,
the DELETED watch events signal to the scheduler that CPU has been freed, causing it to
move unschedulable pods back to the active queue for immediate re-evaluation.

This is why the broker only re-sends unschedulable pods when completions exist — without
completions, there are no DELETE events to flush the backoff queue.

## Late Binding Safety Net

A race condition can occur if the scheduler binds a pod after the adapter's scheduling
round has already closed (e.g., the scheduler re-evaluates a pod triggered by a DELETE
event, but the round's decision count was already met). The adapter captures these "late
bindings" and merges them into the next round's decision, preventing the pod from being
re-submitted to the scheduler (which would ignore it since it's already bound).

If a late binding occurs, the adapter logs a WARNING. Under normal operation with the
current design (only re-sending pods when completions exist), this should not trigger.
