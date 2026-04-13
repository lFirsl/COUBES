# CloudSim 7G — Architecture & Usage Knowledge

## What CloudSim 7G Is

CloudSim 7G is the seventh generation of the CloudSim discrete-event cloud simulation toolkit (Java 21, Maven). It is a major re-engineering of CloudSim 6G, introducing generalized interfaces that allow multiple modules (power, containers, networking) to coexist in the same simulation. Key improvements: ~13,000 lines of code removed, up to 25% less heap memory, faster event dispatch (PriorityQueue instead of LinkedList).

Source: `/home/flori/COUBES/cloudsim-7.0/`
**Never modify this directory** — it is the upstream reference.

---

## Core Simulation Flow

Every CloudSim simulation follows this sequence:

```
1. CloudSim.init(numUsers, calendar, traceFlag)
2. Create Datacenter(s)  ← hosts, PEs, characteristics
3. Create DatacenterBroker
4. Create VMs → broker.submitGuestList(vmList)
5. Create Cloudlets → broker.submitCloudletList(cloudletList)
6. CloudSim.startSimulation()
7. CloudSim.stopSimulation()
8. broker.getCloudletReceivedList()  ← results
```

The simulation terminates when all submitted cloudlets have completed.

---

## Key Classes

### Physical Layer

**`Pe`** — Processing Element (CPU core). Each has an ID and MIPS rating.
```java
new Pe(id, new PeProvisionerSimple(mips))
```
**CRITICAL**: Each `Host` must have its **own independent `List<Pe>`**. Never share a `Pe` list across multiple hosts — `VmSchedulerTimeShared` tracks PE usage per-host using the same `Pe` object references, causing silent allocation failures for all hosts sharing the list.

**`Host`** / **`PowerHost`** — Physical machine. Contains PEs, RAM, BW, storage, a `VmScheduler`.
```java
new PowerHost(id, new RamProvisionerSimple(ram), new BwProvisionerSimple(bw),
              storage, peList, new VmSchedulerTimeShared(peList), powerModel)
```

**`VmSchedulerTimeShared`** — Shares host MIPS across all VMs time-shared.
**`VmSchedulerSpaceShared`** — Allocates dedicated PEs to VMs (no sharing).

**`DatacenterCharacteristics`** — Bundles datacenter metadata (arch, OS, VMM, hosts, cost).

**`Datacenter`** / **`PowerDatacenter`** — The resource provider. Created with a `VmAllocationPolicy`.
- Scheduling interval (last constructor arg): how often `updateCloudletProcessing()` is called (in simulated seconds). Use `1` for fine-grained energy tracking, `0` for event-driven only.

**`VmAllocationPolicySimple`** — First-fit allocation. Places VMs onto the first host with sufficient resources (MIPS, RAM, BW). Multiple VMs can share a host if it has enough capacity. A host with 4 PEs at 1000 MIPS each can host 4 VMs each requesting 1 PE at 1000 MIPS. The constraint is resource availability, not a hard "1 VM per host" rule.

---

### Virtual Layer

**`Vm`** / **`PowerVm`** / **`PowerVmCustom`** — Virtual machine. Key parameters:
- `id` — must be unique across the simulation
- `userId` — broker ID (from `broker.getId()`)
- `mips` — MIPS per PE
- `pesNumber` — number of PEs requested
- `ram` (MB), `bw`, `size` (MB)
- `CloudletScheduler` — how cloudlets are scheduled on this VM

**`CloudletSchedulerTimeShared`** — All cloudlets on a VM share its MIPS. Start time = submission time (no queuing). Finish time depends on concurrent load.
**`CloudletSchedulerSpaceShared`** — One cloudlet at a time per PE. Others wait.

**`PowerVmCustom`** (COUBES extension) — Adds `preferredHostId` for host-pinning, overrides MIPS demand calculation to reflect active cloudlets.

---

### Workload Layer

**`Cloudlet`** — A task. Key parameters:
- `id` — must be unique
- `length` (MI) — millions of instructions; execution time = length / allocatedMIPS
- `pesNumber` — number of PEs required
- `fileSize`, `outputSize` (bytes)
- `UtilizationModel` × 3 — CPU, RAM, BW utilization over time

**`UtilizationModelFull`** — Always uses 100% of allocated resource.
**`UtilizationModelStochastic`** — Random utilization.

Execution time formula (time-shared, single cloudlet on VM):
```
execTime = cloudletLength / (vmMips * pesNumber)
```

With multiple cloudlets time-sharing a VM with `vmMips` total:
```
execTime = cloudletLength / (vmMips / numConcurrentCloudlets * cloudletPes)
```

---

### Broker Layer

**`DatacenterBroker`** — Base broker. Submits VMs and cloudlets, collects results.
- `submitGuestList(vmList)` — register VMs
- `submitCloudletList(cloudletList)` — submit cloudlets immediately
- `submitCloudletList(cloudletList, delay)` — submit cloudlets at `delay` simulated seconds

**`DatacenterBrokerEX`** (CloudSim EX extension) — Adds delayed submission, dynamic cloudlet arrival.

**`Live_Kubernetes_Broker_Ex`** (COUBES) — Extends `DatacenterBrokerEX`. Delegates cloudlet-to-VM assignment to the K8s adapter instead of CloudSim's internal scheduler.

---

### VM Allocation: How Many VMs Fit

`VmAllocationPolicySimple` uses **first-fit**: it places each VM on the first host with sufficient free resources. Multiple VMs can land on the same host if it has capacity. Therefore:
- A host with 4 PEs at 1000 MIPS can host 4 VMs each requesting 1 PE at 1000 MIPS
- VMs are **not** limited to one per host — the constraint is resource availability
- If no host has enough free resources for a VM, it is silently rejected (no error)
- The broker logs "Trying to Create VM #X" for all submitted VMs, but only logs "VM #X has been created" for successfully allocated ones

**To run N VMs simultaneously, the total cluster resources must be sufficient** — not necessarily N separate hosts.

VM allocation requires the host to have sufficient free:
- MIPS: `host.freeMips >= vm.mips * vm.pesNumber`
- RAM: `host.freeRam >= vm.ram`
- BW: `host.freeBw >= vm.bw`

---

## Critical Gotcha: Pe List Sharing

**The most common bug when creating multiple hosts in a loop:**

```java
// WRONG — all hosts share the same peList object
List<Pe> peList = new ArrayList<>();
peList.add(new Pe(0, new PeProvisionerSimple(mips)));
for (int i = 0; i < N; i++) {
    hostList.add(new Host(i, ..., peList, new VmSchedulerTimeShared(peList)));
}
// Result: only the first host works; VMs fail to allocate on hosts 1..N-1
```

```java
// CORRECT — fresh peList per host
for (int i = 0; i < N; i++) {
    List<Pe> peList = new ArrayList<>();
    for (int p = 0; p < numPes; p++) {
        peList.add(new Pe(p, new PeProvisionerSimple(mips)));
    }
    hostList.add(new Host(i, ..., peList, new VmSchedulerTimeShared(peList)));
}
```

---

## Power Modelling

**`PowerHost`** — Extends `Host` with a `PowerModel`.
**`PowerModel`** — Maps CPU utilization (0.0–1.0) to watts.
- `PowerModelLinear(maxWatts, staticPowerPercent)` — linear between `maxWatts * staticPowerPercent` (idle) and `maxWatts` (full load)
- `PowerModelSpecPowerHpProLiantMl110G4Xeon3040` — real HP server spec table

Energy is accumulated as W·sec at each scheduling interval using linear interpolation between utilization samples. Convert to Wh: `datacenter.getPower() / 3600`.

**`PowerDatacenter`** — Extends `Datacenter` with energy tracking. Calls `updateCloudletProcessing()` at each scheduling interval.

**`PowerDatacenterCustom`** (COUBES) — Adds:
- Consolidation ratio tracking (`TimeWeightedMetric`)
- `disableDeallocation` flag: when `true`, VMs are never auto-destroyed (needed for fragmentation tests)
- `VM_DELAYED_DESTROY` tag for deferred VM destruction

---

## Scheduling Interval

The `schedulingInterval` parameter in `PowerDatacenter` (and `PowerDatacenterCustom`) controls how often the datacenter processes cloudlet updates and energy accounting:
- `0` = event-driven only (no periodic updates)
- `1` = every 1 simulated second (fine-grained energy tracking)
- Higher values = coarser energy tracking but faster simulation

COUBES tests use `1` for accurate energy metrics.

---

## Cloudlet Submission Timing

```java
broker.submitCloudletList(list);          // submit at t=0
broker.submitCloudletList(list, delay);   // submit at t=delay (simulated seconds)
```

When using delayed submission, `CloudSim.resumeSimulation()` must be called before `CloudSim.startSimulation()` to allow the simulation to proceed past any pause points.

---

## Entity ID Assignment

CloudSim assigns entity IDs sequentially at creation time:
- ID 0: `CloudSimShutDown` (internal)
- ID 1: `CloudInformationService` (internal)
- ID 2: First `Datacenter`
- ID 3: First `DatacenterBroker`
- etc.

`CloudSim.init(numUsers, ...)` — `numUsers` affects how many shutdown acknowledgements the simulation waits for. Use `2` for COUBES tests (matches the existing pattern).

VM IDs and Cloudlet IDs are user-assigned and must be unique within the simulation. The `idShift` pattern in COUBES tests avoids collisions between batches:
```java
createVM(brokerId, 5, 0);    // VMs 0-4
createVM(brokerId, 5, 100);  // VMs 100-104 (for a second broker)
createCloudlet(brokerId, 10, 0);    // Cloudlets 0-9
createCloudlet(brokerId, 5, 100);   // Cloudlets 100-104
```

---

## Examples in cloudsim-7.0

| Example | What it shows |
|---|---|
| `CloudSimExample1` | Minimal: 1 host, 1 VM, 1 cloudlet |
| `CloudSimExample2` | Multiple VMs and cloudlets |
| `CloudSimExample7` | Pause/resume + dynamic broker creation |
| `power/Helper.java` | Factory methods for power-aware VMs/hosts |
| `CloudSimMultiExtensionExample1` | VMs + containers in same simulation |

---

## COUBES-Specific Patterns

### Creating a test scenario

```java
CloudSim.init(2, Calendar.getInstance(), false);
PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
int brokerId = broker.getId();

// VMs
List<Vm> vms = createVMs(brokerId, numVMs, 0);
broker.submitGuestList(vms);

// Wave 1 cloudlets (immediate)
List<Cloudlet> wave1 = createCloudlets(brokerId, n1, length1, pes1, 0);
broker.submitCloudletList(wave1);

// Wave 2 cloudlets (delayed)
List<Cloudlet> wave2 = createCloudlets(brokerId, n2, length2, pes2, 100); // idShift=100
broker.submitCloudletList(wave2, delaySeconds);

CloudSim.resumeSimulation();
SimulationMetrics metrics = new SimulationMetrics(dc, vms);
metrics.startWallClock();
double lastClock = CloudSim.startSimulation();
// ... collect results ...
broker.sendResetRequestToControlPlane();  // always reset adapter at end
```

### Datacenter creation (correct Pe list pattern)

```java
List<Host> hostList = new ArrayList<>();
for (int i = 0; i < numHosts; i++) {
    List<Pe> peList = new ArrayList<>();  // NEW list per host
    for (int p = 0; p < numPes; p++) {
        peList.add(new Pe(p, new PeProvisionerSimple(mips)));
    }
    hostList.add(new PowerHost(i,
        new RamProvisionerSimple(ram),
        new BwProvisionerSimple(bw),
        storage, peList,
        new VmSchedulerTimeShared(peList),
        new PowerModelLinear(maxWatts, staticFraction)));
}
DatacenterCharacteristics chars = new DatacenterCharacteristics(
    "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
PowerDatacenterCustom dc = new PowerDatacenterCustom(
    name, chars, new VmAllocationPolicySimple(hostList),
    new LinkedList<>(), 1, true);  // schedulingInterval=1, disableDeallocation=true
dc.setDisableMigrations(true);
```

### VM creation (COUBES)

```java
new PowerVmCustom(id, userId, mips, pesNumber, ram, bw, size,
                  0, "Xen", new CloudletSchedulerTimeShared(),
                  schedulingInterval, preferredHostId)
// preferredHostId = -1 for no preference
```

---

## Known Constraints

- `VmAllocationPolicySimple`: 1 VM per host. Need N hosts for N VMs.
- Cloudlet IDs must be globally unique across all batches in a simulation run.
- `disableDeallocation=true` means VMs persist even after their cloudlets finish — required for fragmentation tests where VMs must remain to accept rescheduled cloudlets.
- The `UtilizationModelSlice` class has a known bug: `Math.min(0, 1/PEs)` always returns 0.
- `Live_Kubernetes_Broker` (old) has a double-submit bug — always use `Live_Kubernetes_Broker_Ex`.
