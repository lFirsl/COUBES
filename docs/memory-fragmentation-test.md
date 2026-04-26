# Memory Fragmentation Test — Design Document

## Purpose

This test demonstrates how different scheduling strategies (LeastAllocated vs MostAllocated) produce different memory fragmentation outcomes, even when the total free RAM in the cluster is sufficient for all pods.

## Cluster Setup

| Resource | Value |
|---|---|
| VMs | 4 |
| PEs per VM | 4 |
| RAM per VM | 1024 MB |
| Total cluster RAM | 4096 MB |
| Total cluster PEs | 16 |

CPU is intentionally oversized so that **memory is the only binding constraint**.

## Workload

### Wave 1 (t=0)

| Count | PEs | RAM | Total RAM |
|---|---|---|---|
| 4 pods | 1 | 384 MB | 1536 MB |

After wave 1, the cluster has 2560 MB free RAM and 12 free PEs.

### Wave 2 (t=50)

| Count | PEs | RAM | Total RAM |
|---|---|---|---|
| 4 pods | 1 | 640 MB | 2560 MB |

Wave 2 demands exactly the remaining free RAM. Whether it fits depends on *how* wave 1 was distributed.

## Expected Outcomes

### LeastAllocated (spreading)

The scheduler distributes wave 1 evenly — one pod per VM:

```
VM0: 384 MB used → 640 MB free
VM1: 384 MB used → 640 MB free
VM2: 384 MB used → 640 MB free
VM3: 384 MB used → 640 MB free
```

Each VM has exactly 640 MB free. Each wave-2 pod needs 640 MB. **All 4 wave-2 pods fit immediately.** No rescheduling needed.

- Scheduling rounds: 2 (wave 1 + wave 2)
- All 8 pods complete without delay

### MostAllocated (bin-packing)

The scheduler packs wave 1 tightly — two pods per VM on the first two VMs:

```
VM0: 768 MB used (2 × 384) → 256 MB free
VM1: 768 MB used (2 × 384) → 256 MB free
VM2:   0 MB used            → 1024 MB free
VM3:   0 MB used            → 1024 MB free
```

Total free RAM is still 2560 MB, but it's distributed unevenly:
- VM0 and VM1 each have 256 MB free — **too small** for a 640 MB pod
- VM2 and VM3 each have 1024 MB free — fits one 640 MB pod each

**Only 2 of 4 wave-2 pods fit immediately.** The other 2 are unschedulable until wave 1 completes and frees RAM on VM0/VM1. This triggers rescheduling.

- Scheduling rounds: 3+ (wave 1 + wave 2 partial + rescheduling after wave 1 completes)
- Wave-2 pods 100/101 are delayed until ~t=161 (after wave 1 finishes)

### Summary

| Metric | LeastAllocated | MostAllocated |
|---|---|---|
| Wave-2 pods scheduled immediately | 4 | 2 |
| Wave-2 pods delayed (rescheduled) | 0 | 2 |
| Scheduling rounds | 2 | 3+ |
| Simulated completion time | ~211 | ~322 |
| Memory fragmentation | None | 512 MB in unusable 256 MB fragments |

## Why This Matters

This is the classic memory fragmentation problem in scheduling. The bin-packing strategy optimises for consolidation (fewer active nodes, better energy efficiency), but creates small free-memory fragments that can't accommodate larger pods. The spreading strategy sacrifices consolidation for uniform free capacity, avoiding fragmentation.

In production Kubernetes clusters, this tradeoff affects:
- **Pod scheduling latency** — fragmented clusters cause pods to wait in the scheduler's backoff queue
- **Cluster autoscaler triggers** — fragmentation can trigger unnecessary scale-up even when total capacity is sufficient
- **SLA compliance** — delayed pod placement means delayed workload start

## How to Run

```bash
# Test mode (round-robin ≈ spreading): all wave-2 pods fit immediately
bash run_test.sh --test-mode org.example.testSuite.Memory_Fragmentation_Test

# Full mode with LeastAllocated (spreading): all wave-2 pods fit immediately
# (start adapter with --scheduler=default-scheduler)
bash run_test.sh org.example.testSuite.Memory_Fragmentation_Test

# Full mode with MostAllocated (bin-packing): 2 wave-2 pods delayed
# (start adapter with --scheduler=my-scheduler)
bash run_test.sh org.example.testSuite.Memory_Fragmentation_Test
```

To switch between scheduler profiles in full mode, change the `--scheduler` flag when starting the adapter, or modify `ADAPTER_FLAGS` in `run_test.sh`. The `second-scheduler/my-scheduler.yaml` config defines both profiles with equal CPU and memory weights.

## Related Files

- Test class: `src/main/java/org/example/testSuite/Memory_Fragmentation_Test.java`
- Scheduler config: `second-scheduler/my-scheduler.yaml`
- CoubesCloudlet (RAM request): `src/main/java/org/example/kubernetes_broker/CoubesCloudlet.java`
- Test mode RAM tracking: `k8s-cloudsim-adapter/scheduler/test_mode_scheduler.go`
