package org.example.testSuite;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.kubernetes_broker.*;
import org.example.metrics.SimulationMetrics;

import java.util.*;

/**
 * Queue Starvation Test — sustained overload showing proportion plugin vs greedy scheduling.
 *
 * Setup: 4 VMs × 4 PEs = 16 PE slots (8 pod slots at 2 PEs each)
 *
 * Wave 1 (t=0):   12 high-priority (classType=1) + 4 batch (classType=2), all 2 PEs
 * Wave 2 (t=170): 8 more high-priority
 * Wave 3 (t=340): 4 more high-priority
 *
 * Total: 24 high-priority + 4 batch = 28 pods, but only 8 slots at a time.
 * High-priority pods keep arriving, creating sustained pressure.
 *
 * Volcano (proportion 3:1): In every round, batch gets ~2/8 slots (25%).
 *   Batch pods start in round 1 and finish by ~t=160.
 * kube-scheduler: Schedules high-priority first (no queue concept). Batch pods
 *   can only run when high-priority demand drops below capacity — which doesn't
 *   happen until wave 3 is nearly done (~t=500+). Batch wait time is enormous.
 *
 * Key metric: Batch avg wait time. Volcano ≈ 0s, kube-scheduler ≈ 300-500s.
 */
public class Queue_Starvation_Test {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        int numHosts = 4;
        int numPes = 4;
        int mips = 250;

        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < numPes; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(2048), new BwProvisionerSimple(10000),
                    100000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = new PowerDatacenterCustom(
                "Datacenter_0", chars, new VmAllocationPolicySimple(hostList),
                new LinkedList<>(), 1, true);
        dc.setDisableMigrations(true);

        Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0");
        int brokerId = broker.getId();

        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            vmList.add(new PowerVmCustom(i, brokerId, mips, numPes, 2048, 10000, 100000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 1, i));
        }
        broker.submitGuestList(vmList);

        UtilizationModel full = new UtilizationModelFull();
        int id = 0;

        // Wave 1 (t=0): 12 high-priority + 4 batch
        List<Cloudlet> wave1 = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 40000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(1);
            wave1.add(cl);
        }
        for (int i = 0; i < 4; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 40000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(2);
            wave1.add(cl);
        }
        broker.submitCloudletList(wave1);

        // Wave 2 (t=170): 8 more high-priority (arrives just after wave 1 starts completing)
        List<Cloudlet> wave2 = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 40000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(1);
            wave2.add(cl);
        }
        broker.submitCloudletList(wave2, 170);

        // Wave 3 (t=340): 4 more high-priority
        List<Cloudlet> wave3 = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            CoubesCloudlet cl = new CoubesCloudlet(id++, 40000, 2, 300, 300, full, full, full, 0);
            cl.setClassType(1);
            wave3.add(cl);
        }
        broker.submitCloudletList(wave3, 340);

        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Queue_Starvation_Test Results ==========");

        // Summary by queue
        double batchWaitSum = 0, hpWaitSum = 0;
        int batchCount = 0, hpCount = 0;
        for (Cloudlet cl : results) {
            double wait = cl.getExecStartTime() - cl.getSubmissionTime();
            if (cl.getClassType() == 1) { hpWaitSum += wait; hpCount++; }
            else if (cl.getClassType() == 2) { batchWaitSum += wait; batchCount++; }
        }
        Log.printlnConcat("High-priority: ", hpCount, " completed, avg wait = ",
                String.format("%.1f", hpCount > 0 ? hpWaitSum / hpCount : 0), "s");
        Log.printlnConcat("Batch:         ", batchCount, " completed, avg wait = ",
                String.format("%.1f", batchCount > 0 ? batchWaitSum / batchCount : 0), "s");

        metrics.setCompletedCloudlets(results, broker.getCloudletArrivalTimes());
        metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());
        SimulationMetrics.printPerQueueMetrics(results, broker.getCloudletArrivalTimes());

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nQueue_Starvation_Test finished!");
    }
}
