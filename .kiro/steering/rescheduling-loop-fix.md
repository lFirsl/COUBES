---
inclusion: manual
---

# Rescheduling Loop Bug — Full Investigation & Fix Record

## Background

The rescheduling loop is the mechanism that allows COUBES to handle more cloudlets than
available VM capacity. When a batch of cloudlets is submitted and some can't be scheduled
(no free nodes), they stay pending. When running cloudlets complete and free up capacity,
the broker triggers a rescheduling round to place the pending cloudlets.

This bug blocked `Fragmentation_Test_Large` (50 cloudlets, 2 VMs × 5 PEs) and
`Scheduler_Latency_Test` (120 cloudlets, 20 VMs). Both require multiple rescheduling
rounds to complete all cloudlets.

---

## The Four Problems (in order of discovery)

### 1. Test mode scheduler was not resource-aware

**Symptom:** All pods assigned round-robin regardless of node capacity. Unschedulable pods
never returned. Rescheduling loop never triggered.

**Root cause:** `TestModeScheduler.Schedule()` did `pod[i] → node[i % N]` unconditionally.

**Fix (test_mode_scheduler.go):** Rewrote `Schedule()` to track `freePes[]` per node.
For each pod, try nodes starting from the round-robin index. First node with
`freePes >= pod.Pes` gets the pod. If no node fits → unschedulable. `SchedulerPod` and
`SchedulerNode` structs gained a `Pes int` field.

### 2. Adapter didn't track running pods between rounds

**Symptom:** Round 2 saw full node capacity (as if nothing was running), so it scheduled
pods onto already-full nodes.

**Root cause:** `scheduleTestMode()` passed each node's total PEs to the scheduler. It
didn't subtract PEs consumed by pods scheduled in previous rounds.

**Fix (communicator.go):**
- Before calling the scheduler, scan `c.pods` for pods with `Status == "Running"` and
  sum their PEs per node. Subtract from total to get free PEs.
- After scheduling, update `c.pods[podID].Status = "Running"` and `.NodeID` so future
  rounds see the occupied capacity.

### 3. Java broker didn't re-send pending pods in the snapshot

**Symptom:** Adapter log showed `podCount: 0` for the rescheduling round despite 40
cloudlets waiting.

**Root cause:** `submitCloudlets()` built the snapshot's pod array from `getCloudletList()`
only. But pending (previously unschedulable) cloudlets had been moved to
`cloudletsSubmittedToMiddle` in round 1 and were never put back into `getCloudletList()`.
So the rescheduling round sent an empty pod list.

**Fix (Live_Kubernetes_Broker_Ex.java):** When building the snapshot pod array, iterate
both `getCloudletList()` (newly arrived) and `cloudletsSubmittedToMiddle` (previously
pending). Added `buildPodJson()` helper to avoid duplication.

### 4. CloudSim event chain died after cloudlets arrived on idle VMs

**Symptom:** Cloudlets were successfully sent to CloudSim VMs via `sendNow(CLOUDLET_SUBMIT)`,
the VMs had 5 cloudlets each in their exec queue, but the simulation terminated with
"No more future events" one tick later.

**Root cause:** This is a timing issue in CloudSim 7G's `PowerDatacenter` /
`HostDynamicWorkload` interaction.

The datacenter keeps itself alive by scheduling periodic `VM_DATACENTER_EVENT` events.
Each tick, it calls `host.updateCloudletsProcessing(currentTime)` which returns the next
estimated completion time (`minTime`). If `minTime != MAX_VALUE`, it schedules the next
event. If `minTime == MAX_VALUE`, the chain stops.

`HostDynamicWorkload.updateCloudletsProcessing()` works like this:
```
1. smallerTime = super.updateCloudletsProcessing(currentTime)
   → calls vm.updateCloudletsProcessing(currentTime, allocatedMips)
   → allocatedMips comes from the PREVIOUS cycle's allocation
2. deallocate all PEs
3. reallocate PEs based on vm.getCurrentRequestedMips()
```

The problem: step 1 uses the *old* MIPS allocation. When VMs were idle (all cloudlets
finished), their allocation was 0 MIPS. When new cloudlets arrive on these idle VMs, the
first `updateCloudletsProcessing` call processes them with 0 MIPS. The cloudlet scheduler
can't make progress, returns `MAX_VALUE`. The datacenter sees `MAX_VALUE`, doesn't schedule
the next event, and the simulation dies.

Step 3 *does* reallocate the correct MIPS (because the VMs now have active cloudlets
requesting MIPS), but it's too late — the return value from step 1 already killed the
event chain.

**Fix (PowerDatacenterCustom.java):** Added a safety net in
`updateCloudetProcessingWithoutSchedulingFutureEventsForce()`. After the host processing
loop, if `minTime == MAX_VALUE`, check whether any VM has cloudlets in its exec list.
If so, force `minTime = currentTime + schedulingInterval`. On the next tick, the MIPS
allocation will have been refreshed by step 3, and processing continues normally.

```java
if (minTime == Double.MAX_VALUE) {
    outer:
    for (HostEntity host : getVmAllocationPolicy().getHostList()) {
        for (GuestEntity vm : host.getGuestList()) {
            if (!vm.getCloudletScheduler().getCloudletExecList().isEmpty()) {
                minTime = currentTime + getSchedulingInterval();
                break outer;
            }
        }
    }
}
```

This is safe because:
- If VMs truly have no cloudlets, `minTime` stays `MAX_VALUE` and the chain stops (correct).
- If VMs have cloudlets but `minTime` was wrong due to stale MIPS, we force one more tick.
  The next tick will have correct MIPS and return a real `minTime`.

---

## Files Modified

| File | Change |
|---|---|
| `k8s-cloudsim-adapter/scheduler/test_mode_scheduler.go` | Resource-aware scheduling with PE tracking |
| `k8s-cloudsim-adapter/communicator/communicator.go` | Track running pods' PE usage; update pod status after scheduling |
| `k8s-cloudsim-adapter/scheduler/test_mode_scheduler_test.go` | Fixed `TestProperty2` to use node ID field; added 3 resource-aware tests |
| `src/.../Live_Kubernetes_Broker_Ex.java` | Include `cloudletsSubmittedToMiddle` in snapshot; `buildPodJson` helper |
| `src/.../PowerDatacenterCustom.java` | Safety net for stale-MIPS event chain death |

---

## Test Evidence

`Fragmentation_Test_Large` (2 hosts × 5 PEs, 10 VMs, 50 cloudlets):
- Round 1 (t=0): 10 scheduled, 25 unschedulable
- Round 2 (t=50): 0 scheduled (wave 2 arrives, nodes full), 40 unschedulable
- Round 3 (t=161): 10 scheduled (after wave 1 completes), 30 unschedulable
- Round 4 (t=323): 10 scheduled, 20 unschedulable
- ...continues until all 50 complete at t=3689
- Energy: 841.60 Wh, consolidation: 4.02

Go tests: 19/19 pass (including 3 new resource-aware tests).

---

## If This Breaks Again

The most likely failure modes:

1. **Pods scheduled but simulation dies immediately** → Problem 4 (event chain).
   Check if `PowerDatacenterCustom`'s safety net is still in place. Add the DEBUG logging
   back (print `minTime`, VM exec list sizes) to confirm.

2. **Adapter returns 0 scheduled in rescheduling round** → Problem 3 (empty snapshot).
   Check that `submitCloudlets()` includes `cloudletsSubmittedToMiddle` entries in the
   pod array. Check adapter log for `podCount` in the `HandleSchedule` entry.

3. **All pods scheduled even when nodes are full** → Problem 1 or 2 (capacity tracking).
   Check adapter log for `testModeNodeCapacity` entries showing `freePes` and `usedPes`.
   If `usedPes` is 0 when it shouldn't be, check that `c.pods` status is being updated
   to "Running" after scheduling.

4. **Rescheduling event never fires** → Check `processCloudletReturn` in the broker.
   It should schedule `RESCHEDULE_PENDING` at +1s when `cloudletsSubmittedToMiddle` is
   non-empty. Check that `reschedulePending` flag is being reset in `processOtherEvent`.
