package org.example.kubernetes_broker;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.*;
import org.cloudbus.cloudsim.core.predicates.PredicateType;
import org.cloudbus.cloudsim.power.PowerDatacenter;
import org.cloudbus.cloudsim.power.PowerHost;
import org.example.metrics.TimeWeightedMetric;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PowerDatacenterCustom extends PowerDatacenter {

    /** Controls per-tick log verbosity. Set via {@link #setLogLevel(LogLevel)}. */
    public enum LogLevel { QUIET, NORMAL, VERBOSE }

    private LogLevel logLevel = LogLevel.NORMAL;

    double totalUsedMips = 0;
    double totalCapacity = 0;
    Set<Integer> totalVmIdsEverAllocated;
    private final TimeWeightedMetric consolidationTW = new TimeWeightedMetric();
    boolean disableDeallocation;

    public PowerDatacenterCustom(String name, DatacenterCharacteristics characteristics, VmAllocationPolicy vmAllocationPolicy, List<Storage> storageList, double schedulingInterval) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
        totalVmIdsEverAllocated = new HashSet<Integer>();
        disableDeallocation = false;
    }

    public PowerDatacenterCustom(String name, DatacenterCharacteristics characteristics, VmAllocationPolicy vmAllocationPolicy, List<Storage> storageList, double schedulingInterval, boolean disableDeallocation) throws Exception {
        super(name, characteristics, vmAllocationPolicy, storageList, schedulingInterval);
        totalVmIdsEverAllocated = new HashSet<Integer>();
        this.disableDeallocation = disableDeallocation;
    }

    public void setLogLevel(LogLevel level) { this.logLevel = level; }
    public LogLevel getLogLevel() { return logLevel; }

    @Override
    protected void updateCloudletProcessing() {
        if (getCloudletSubmitted() == -1 || getCloudletSubmitted() == CloudSim.clock()) {
            CloudSim.cancelAll(getId(), new PredicateType(CloudActionTags.VM_DATACENTER_EVENT));
            schedule(getId(), getSchedulingInterval(), CloudActionTags.VM_DATACENTER_EVENT);
            return;
        }
        double currentTime = CloudSim.clock();

        // Consolidation ratio
        int activeVMs = 0;
        int activeCloudlets = 0;
        for (HostEntity host : getVmAllocationPolicy().getHostList()) {
            for (GuestEntity vm : host.getGuestList()) {
                int cloudletCount = vm.getCloudletScheduler().getCloudletExecList().size()
                        + vm.getCloudletScheduler().getCloudletWaitingList().size();
                if (cloudletCount > 0) {
                    activeVMs++;
                    activeCloudlets += cloudletCount;
                }
            }
        }

        double consolidationRatio = 0;
        if (activeVMs != 0 && activeCloudlets != 0) {
            consolidationRatio = (double) activeCloudlets / activeVMs;
            if (logLevel == LogLevel.VERBOSE) {
                Log.printlnConcat(String.format("%.2f", currentTime),
                        ": consolidation=", String.format("%.2f", consolidationRatio),
                        " (", activeCloudlets, " cloudlets / ", activeVMs, " VMs)");
            }
            consolidationTW.add(CloudSim.clock(), consolidationRatio);
        } else {
            if (logLevel == LogLevel.VERBOSE) {
                Log.printlnConcat(String.format("%.2f", currentTime), ": No active VMs for consolidation");
            }
        }

        if (currentTime > getLastProcessTime()) {
            if (logLevel == LogLevel.VERBOSE) {
                Log.print(currentTime + " ");
            }

            double minTime = updateCloudetProcessingWithoutSchedulingFutureEventsForce();

            if (!isDisableMigrations()) {
                List<VmAllocationPolicy.GuestMapping> migrationMap = getVmAllocationPolicy().optimizeAllocation(getVmList());
                if (migrationMap != null) {
                    for (VmAllocationPolicy.GuestMapping migrate : migrationMap) {
                        Vm vm = (Vm) migrate.vm();
                        PowerHost targetHost = (PowerHost) migrate.host();
                        PowerHost oldHost = (PowerHost) vm.getHost();
                        if (oldHost == null) {
                            Log.formatLine("%.2f: Migration of VM #%d to Host #%d is started",
                                    currentTime, vm.getId(), targetHost.getId());
                        } else {
                            Log.formatLine("%.2f: Migration of VM #%d from Host #%d to Host #%d is started",
                                    currentTime, vm.getId(), oldHost.getId(), targetHost.getId());
                        }
                        targetHost.addMigratingInGuest(vm);
                        incrementMigrationCount();
                        send(getId(), vm.getRam() / ((double) targetHost.getBw() / (2 * 8000)),
                                CloudActionTags.VM_MIGRATE, migrate);
                    }
                }
            }

            if (minTime != Double.MAX_VALUE) {
                CloudSim.cancelAll(getId(), new PredicateType(CloudActionTags.VM_DATACENTER_EVENT));
                send(getId(), getSchedulingInterval(), CloudActionTags.VM_DATACENTER_EVENT);
            } else {
                Log.printlnConcat(String.format("%.2f", currentTime), ": [DEBUG] minTime=MAX_VALUE, no future event scheduled. cloudletsSubmitted=", getCloudletSubmitted());
                for (PowerHost host : this.<PowerHost>getHostList()) {
                    for (GuestEntity vm : host.getGuestList()) {
                        Log.printlnConcat(String.format("%.2f", currentTime), ": [DEBUG] VM #", vm.getId(),
                            " exec=", vm.getCloudletScheduler().getCloudletExecList().size(),
                            " wait=", vm.getCloudletScheduler().getCloudletWaitingList().size());
                    }
                }
            }

            setLastProcessTime(currentTime);
        }
    }

    public double getConsolidationAverage(double time) {
        return consolidationTW.average(time);
    }

    @Override
    protected double updateCloudetProcessingWithoutSchedulingFutureEventsForce() {
        double currentTime = CloudSim.clock();
        double minTime = Double.MAX_VALUE;
        double timeDiff = currentTime - getLastProcessTime();
        double timeFrameDatacenterEnergy = 0.0;

        if (logLevel == LogLevel.VERBOSE) {
            Log.println("\n\n--------------------------------------------------------------\n\n");
            Log.formatLine("New resource usage for the time frame starting at %.2f:", currentTime);
        }

        for (PowerHost host : this.<PowerHost>getHostList()) {
            if (logLevel == LogLevel.VERBOSE) Log.println();
            double time = host.updateCloudletsProcessing(currentTime);
            if (time < minTime) minTime = time;

            if (logLevel == LogLevel.VERBOSE) {
                Log.formatLine("%.2f: [Host #%d] utilization is %.2f%%",
                        currentTime, host.getId(), host.getUtilizationOfCpu() * 100);
            }
        }

        // Safety net: if any VM has active cloudlets but minTime is MAX_VALUE
        // (happens when cloudlets arrive on previously-idle VMs whose MIPS
        // allocation was zero from the prior cycle), force a scheduling event
        // so the next cycle picks up the refreshed MIPS allocation.
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

        if (timeDiff > 0) {
            if (logLevel == LogLevel.VERBOSE) {
                Log.formatLine("\nEnergy consumption for the last time frame from %.2f to %.2f:",
                        getLastProcessTime(), currentTime);
            }

            for (PowerHost host : this.<PowerHost>getHostList()) {
                double previousUtilizationOfCpu = host.getPreviousUtilizationOfCpu();
                double utilizationOfCpu = host.getUtilizationOfCpu();
                double timeFrameHostEnergy = host.getEnergyLinearInterpolation(
                        previousUtilizationOfCpu, utilizationOfCpu, timeDiff);
                timeFrameDatacenterEnergy += timeFrameHostEnergy;

                if (logLevel == LogLevel.VERBOSE) {
                    Log.println();
                    Log.formatLine("%.2f: [Host #%d] utilization at %.2f was %.2f%%, now is %.2f%%",
                            currentTime, host.getId(), getLastProcessTime(),
                            previousUtilizationOfCpu * 100, utilizationOfCpu * 100);
                    Log.formatLine("%.2f: [Host #%d] energy is %.2f W*sec",
                            currentTime, host.getId(), timeFrameHostEnergy);
                }
            }

            if (logLevel == LogLevel.VERBOSE) {
                Log.formatLine("\n%.2f: Data center's energy is %.2f W*sec\n",
                        currentTime, timeFrameDatacenterEnergy);
            }
        }

        setPower(getPower() + timeFrameDatacenterEnergy);

        if (!disableDeallocation) {
            for (PowerHost host : this.<PowerHost>getHostList()) {
                for (GuestEntity guest : new ArrayList<GuestEntity>(host.getGuestList())) {
                    if (guest.isInMigration()) continue;
                    if (guest instanceof Vm vm) {
                        CloudletScheduler scheduler = vm.getCloudletScheduler();
                        boolean hasActiveCloudlets =
                                !scheduler.getCloudletExecList().isEmpty() ||
                                !scheduler.getCloudletWaitingList().isEmpty() ||
                                !scheduler.getCloudletFinishedList().isEmpty();
                        if (!hasActiveCloudlets) {
                            send(this.getId(), 1, CloudActionTagsEx.VM_DELAYED_DESTROY, vm);
                        }
                    }
                }
            }
        }

        if (logLevel == LogLevel.VERBOSE) Log.println();

        setLastProcessTime(currentTime);
        return minTime;
    }

    @Override
    protected void processVmCreate(SimEvent ev, boolean ack) {
        Vm vm = (Vm) ev.getData();
        totalVmIdsEverAllocated.add(vm.getId());

        if (vm instanceof PowerVmCustom pvm) {
            if (pvm.getPreferredHostId() != -1) {
                Log.println(this.getName() + ": Creating PowerVMCustom with preferred host allocation.");
                int targetHostId = pvm.getPreferredHostId();
                HostEntity targetHost = getHostList().get(targetHostId);

                boolean result = false;
                if (targetHost != null && getVmAllocationPolicy().getHostList().contains(targetHost)) {
                    result = getVmAllocationPolicy().allocateHostForGuest(pvm, targetHost);
                } else {
                    result = getVmAllocationPolicy().allocateHostForGuest(pvm);
                }

                if (ack) {
                    int[] data = new int[]{getId(), pvm.getId(), result ? CloudSimTags.TRUE : CloudSimTags.FALSE};
                    send(pvm.getUserId(), CloudSim.getMinTimeBetweenEvents(), CloudActionTags.VM_CREATE_ACK, data);
                }

                if (result) {
                    getVmList().add(pvm);
                    if (pvm.isBeingInstantiated()) pvm.setBeingInstantiated(false);
                    pvm.updateCloudletsProcessing(CloudSim.clock(),
                            getVmAllocationPolicy().getHost(pvm).getGuestScheduler().getAllocatedMipsForGuest(pvm));
                } else {
                    Log.printlnConcat(CloudSim.clock(), ": Datacenter.guestAllocator: Couldn't find a host for PowerVMCustom #", pvm.getId());
                }
                return;
            }
        }
        super.processVmCreate(ev, ack);
    }

    @Override
    public void processEvent(SimEvent ev) {
        CloudSimTags tag = ev.getTag();
        if (tag == CloudActionTagsEx.VM_DELAYED_DESTROY) {
            scheduleVMDestruction(ev);
            return;
        }
        super.processEvent(ev);
    }

    private void scheduleVMDestruction(SimEvent ev) {
        Vm vm = (Vm) ev.getData();
        CloudletScheduler scheduler = vm.getCloudletScheduler();
        boolean hasActiveCloudlets =
                !scheduler.getCloudletExecList().isEmpty() ||
                !scheduler.getCloudletWaitingList().isEmpty() ||
                !scheduler.getCloudletFinishedList().isEmpty();

        if (!hasActiveCloudlets) {
            Log.println(CloudSim.clock() + ": VM #" + vm.getId() + " has been DEALLOCATED and DESTROYED from host");
            getVmAllocationPolicy().deallocateHostForGuest(vm);
            getVmList().remove(vm);
            int brokerId = vm.getUserId();
            sendNow(brokerId, CloudActionTags.VM_DESTROY_ACK, new int[]{getId(), vm.getId(), CloudSimTags.TRUE});
        } else {
            if (logLevel == LogLevel.VERBOSE) {
                Log.println(CloudSim.clock() + ": VM #" + vm.getId() + " destruction deferred (active cloudlets)");
            }
        }
    }
}
