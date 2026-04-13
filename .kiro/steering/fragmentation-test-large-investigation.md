# Fragmentation_Test_Large — Investigation Notes

## Goal
A test with 10 VMs and 50 cloudlets (35 wave 1 + 15 wave 2) where pods that can't be
immediately scheduled queue in the adapter and are rescheduled as VMs free up.

## Current State (as of 2026-04-13)
The test runs but only 10 cloudlets complete. The rescheduling loop does not fire.

---

## What Was Changed

### `CloudActionTagsEx.java`
Added `RESCHEDULE_PENDING` tag.

### `Live_Kubernetes_Broker_Ex.java` — three changes:

**1. Added `reschedulePending` flag** (line ~37):
```java
private boolean reschedulePending = false;
```

**2. Rewrote `processCloudletReturn`** — removed double-return bug, added deferred reschedule:
- No longer calls `super.processCloudletReturn(ev)` (which caused double-return)
- Schedules `RESCHEDULE_PENDING` event at `+1s` when `cloudletsSubmittedToMiddle` is non-empty
- Calls `finishExecution()` only when truly done (all maps empty, `cloudletsSubmitted == 0`)

**3. Added `processOtherEvent` override** — handles `RESCHEDULE_PENDING` by calling `submitCloudlets()`.

**4. Changed unschedulable pod handling in `processBatchDecision`** — unschedulable pods now
stay in `cloudletsSubmittedToMiddle` (pending queue) instead of being marked FAILED.

### `Fragmentation_Test_Large.java`
- Reduced to 10 VMs (not 20) — intentionally fewer than cloudlets
- 2 hosts × 5 PEs each (fresh Pe lists per host)
- VMs: `pesNumber=5`, `mips=250` (matching `Fragmentation_Test` pattern)
- Wave 1: 35 cloudlets, length=40000, 1 PE
- Wave 2: 15 cloudlets, length=400000, 1 PE, delay=50

---

## Root Cause of Remaining Bug

**`getCloudletList().size() = 10` when `submitCloudlets()` runs**, despite 35 being submitted.

The base `DatacenterBroker.submitCloudlets()` is being called somewhere before
`Live_Kubernetes_Broker_Ex.submitCloudlets()` and consuming 25 cloudlets from the list.

### Evidence
- Debug print in `submitCloudlets()` shows `getCloudletList().size()=10`
- Adapter correctly schedules all pods when given the full list (verified via curl)
- The adapter returns 10 scheduled pods (matching the 10 in the list)
- The other 25 cloudlets are never submitted to the adapter

### Suspected cause
`DatacenterBroker.submitCloudlets()` (base class) is being called before the override.
It submits cloudlets with a bound `guestId` directly to CloudSim and removes them from
`getCloudletList()`. With 35 cloudlets and 10 VMs, it submits 25 (those with `guestId != -1`
or round-robin assignment) and leaves 10.

**Not yet confirmed**: need to check `DatacenterBroker.submitCloudlets()` source to see
exactly which cloudlets it submits and removes, and trace the call path that invokes it.

### What to investigate next
1. Read `DatacenterBroker.submitCloudlets()` — does it only submit cloudlets with a bound VM?
   If so, 25 cloudlets might have `guestId` set from a previous run or from the base
   `processVmCreateAck` path.
2. Check if `DatacenterBroker.processVmCreateAck` is being called in addition to the override.
   The override in `Live_Kubernetes_Broker_Ex` does NOT call `super.processVmCreateAck`.
   But `DatacenterBrokerEX.processEvent` might be calling both.
3. Add a stack trace print inside `DatacenterBroker.submitCloudlets()` to confirm it's being
   called and from where.
4. Alternative: override `submitCloudlets()` to call `super.submitCloudlets()` first and log
   what it does, then proceed with the adapter submission.

---

## Other Findings

### `VmAllocationPolicySimple` is first-fit, not one-per-host
Confirmed by running `CloudSimExample7`: 5 VMs all land on `Host #0` (which has 4 PEs).
Multiple VMs can share a host if it has capacity. The constraint is resource availability.

### Pe list sharing bug
Each host must have its own `List<Pe>` instance. Sharing causes silent allocation failures.
`Fragmentation_Test` reuses `peList1` across all 5 hosts — works by accident because
`VmAllocationPolicySimple` packs all VMs onto the first host.

### Double-return bug (fixed)
`Live_Kubernetes_Broker_Ex.processCloudletReturn` was calling `super.processCloudletReturn(ev)`
in the `else` branch, which caused each cloudlet to be returned twice (once by the override,
once by `DatacenterBrokerEX.processCloudletReturn`). Fixed by removing the `super` call.

### Adapter test mode schedules ALL pods round-robin
`TestModeScheduler.Schedule()` assigns all pods to nodes round-robin — it never returns
unschedulable pods (unless there are no nodes). So the "unschedulable → pending queue"
mechanism is not exercised in test mode. In full mode (real kube-scheduler), pods that
can't be scheduled are returned as unschedulable.

### Simulation ends at t=160 (wave 1 completion)
Wave 2 cloudlets (submitted at t=50) are not completing. Either they're not being submitted
to CloudSim, or the simulation terminates before they finish. The `cloudletsSubmitted`
counter may be going to 0 at t=160 if wave 2 cloudlets were never counted.

---

## Files Modified
- `src/main/java/org/example/kubernetes_broker/CloudActionTagsEx.java`
- `src/main/java/org/example/kubernetes_broker/Live_Kubernetes_Broker_Ex.java`
- `src/main/java/org/example/testSuite/Fragmentation_Test_Large.java`
- `.kiro/steering/cloudsim-7g-knowledge.md` (updated VmAllocationPolicySimple description)
