package org.example.testSuite;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.kubernetes_broker.CoubesCloudlet;
import org.example.kubernetes_broker.Live_Kubernetes_Broker_Ex;
import org.example.kubernetes_broker.PowerDatacenterCustom;
import org.example.kubernetes_broker.PowerVmCustom;
import org.example.metrics.SimulationMetrics;

import java.text.DecimalFormat;
import java.util.*;

/**
 * Queue_Priority_Test — Tests Volcano's multi-queue resource management.
 *
 * Scenario:
 *   - 4 VMs × 4 PEs each = 16 PE slots total
 *   - Wave 1 (t=0): 8 high-priority cloudlets (classType=1, queue="high-priority", 2 PEs each = 16 PEs demanded)
 *   - Wave 2 (t=0): 8 batch cloudlets (classType=2, queue="batch", 2 PEs each = 16 PEs demanded)
 *   - Total demand: 32 PEs, cluster capacity: 16 PEs → both queues compete
 *
 * Expected behaviour:
 *   - Volcano with proportion plugin: high-priority queue (weight 3) gets ~75% of resources,
 *     batch queue (weight 1) gets ~25%. High-priority cloudlets finish faster.
 *   - kube-scheduler (MostAllocated): no queue concept, all 16 pods treated equally,
 *     interleaved scheduling. Both "queues" finish at roughly the same time.
 *
 * Metrics: per-queue turnaround, avg cloudlet turnaround, avg wait time.
 */
public class Queue_Priority_Test {

    public static void main(String[] args) {
        Log.println("Starting Queue_Priority_Test...");

        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            // 4 VMs × 4 PEs @ 250 MIPS
            List<Vm> vms = createVMs(brokerId, 4, 4, 250);
            broker.submitGuestList(vms);

            // Wave 1: 8 high-priority cloudlets (classType=1 → queue "high-priority")
            List<Cloudlet> highPriority = createCloudlets(brokerId, 8, 40000, 2, 1, 0);
            // Wave 2: 8 batch cloudlets (classType=2 → queue "batch")
            List<Cloudlet> batch = createCloudlets(brokerId, 8, 40000, 2, 2, 100);

            // Submit both waves at t=0 so they compete simultaneously
            List<Cloudlet> allCloudlets = new ArrayList<>();
            allCloudlets.addAll(highPriority);
            allCloudlets.addAll(batch);
            broker.submitCloudletList(allCloudlets);

            CloudSim.resumeSimulation();

            SimulationMetrics metrics = new SimulationMetrics(dc, vms);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            List<Cloudlet> completed = broker.getCloudletReceivedList();
            printCloudletList(completed);
            metrics.printSummary(lastClock, broker.tpOverall());
            SimulationMetrics.printPerQueueMetrics(completed);

            broker.sendResetRequestToControlPlane();
            Log.println("Queue_Priority_Test finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.println("The simulation has been terminated due to an unexpected error");
        }
    }

    private static List<Vm> createVMs(int userId, int count, int pes, int mips) {
        List<Vm> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new PowerVmCustom(i, userId, mips, pes, 512, 1000, 10000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 500, -1));
        }
        return list;
    }

    private static List<Cloudlet> createCloudlets(int userId, int count, int length,
                                                   int pes, int classType, int idShift) {
        List<Cloudlet> list = new ArrayList<>();
        UtilizationModel util = new UtilizationModelFull();
        for (int i = 0; i < count; i++) {
            Cloudlet cl = new CoubesCloudlet(idShift + i, length, pes, 300, 300,
                    util, util, util, 64);
            cl.setUserId(userId);
            cl.setClassType(classType);
            list.add(cl);
        }
        return list;
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        int mips = 250;
        int ram = 16384;
        long storage = 1000000;
        int bw = 10000;

        for (int i = 0; i < 4; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 4; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage, peList,
                    new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);

        try {
            PowerDatacenterCustom dc = new PowerDatacenterCustom(
                    name, chars, new VmAllocationPolicySimple(hostList),
                    new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
            return dc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void printCloudletList(List<Cloudlet> list) {
        String indent = "    ";
        Log.println();
        Log.println("========== OUTPUT ==========");
        Log.println("Cloudlet ID" + indent + "STATUS" + indent + "Data center ID" + indent
                + "VM ID" + indent + "Time" + indent + "Start Time" + indent + "Finish Time"
                + indent + "ClassType");

        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet cl : list) {
            Log.print(indent + cl.getCloudletId() + indent + indent);
            if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                Log.println("SUCCESS" + indent + indent + cl.getResourceId()
                        + indent + indent + indent + cl.getGuestId()
                        + indent + indent + indent + dft.format(cl.getActualCPUTime())
                        + indent + indent + dft.format(cl.getExecStartTime())
                        + indent + indent + indent + dft.format(cl.getExecFinishTime())
                        + indent + indent + cl.getClassType());
            }
        }
    }
}
