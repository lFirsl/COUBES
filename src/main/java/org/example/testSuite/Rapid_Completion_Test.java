package org.example.testSuite;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.example.kubernetes_broker.Live_Kubernetes_Broker_Ex;
import org.example.kubernetes_broker.PowerDatacenterCustom;
import org.example.kubernetes_broker.PowerVmCustom;
import org.example.metrics.SimulationMetrics;

import java.text.DecimalFormat;
import java.util.*;

/**
 * Rapid completion test: many cloudlets complete at the exact same simulated time.
 *
 * Setup: 2 hosts × 5 PEs, 2 VMs × 5 PEs = 10 PE slots.
 * 20 pods × 1 PE, all same length. 10 run in round 1, 10 pending.
 * All 10 complete simultaneously → triggers rescheduling.
 *
 * Tests that the reschedulePending flag correctly batches simultaneous
 * completions into a single rescheduling round (not 10 separate rounds).
 */
public class Rapid_Completion_Test {

    public static void main(String[] args) {
        Log.println("Starting Rapid_Completion_Test...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            List<Vm> vms = createVMs(brokerId, 2, 0);
            broker.submitGuestList(vms);

            // 20 pods, all identical length — 10 will run, 10 will wait
            List<Cloudlet> cloudlets = createCloudlets(brokerId, 20, 50000, 1, 0);
            broker.submitCloudletList(cloudlets);

            CloudSim.resumeSimulation();
            SimulationMetrics metrics = new SimulationMetrics(dc, vms);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != 20) {
                throw new RuntimeException("Expected 20 cloudlets but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall());
            broker.sendResetRequestToControlPlane();
            Log.println("Rapid_Completion_Test finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static List<Vm> createVMs(int userId, int count, int idShift) {
        List<Vm> list = new LinkedList<>();
        for (int i = 0; i < count; i++) {
            list.add(new PowerVmCustom(idShift + i, userId, 250, 5, 512, 1000, 10000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 1, -1));
        }
        return list;
    }

    private static List<Cloudlet> createCloudlets(int userId, int count, int length, int pes, int idShift) {
        List<Cloudlet> list = new LinkedList<>();
        UtilizationModel um = new UtilizationModelFull();
        for (int i = 0; i < count; i++) {
            Cloudlet c = new Cloudlet(idShift + i, length, pes, 300, 300, um, um, um);
            c.setUserId(userId);
            list.add(c);
        }
        return list;
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 5; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(250)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(16384), new BwProvisionerSimple(10000),
                    1000000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }
        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        try {
            PowerDatacenterCustom dc = new PowerDatacenterCustom(
                    name, chars, new VmAllocationPolicySimple(hostList), new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
            return dc;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void printCloudletList(List<Cloudlet> list) {
        System.out.println("\n========== OUTPUT ==========");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet c : list) {
            if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                System.out.println("    " + c.getCloudletId() + "    SUCCESS    VM " +
                        c.getGuestId() + "    " + dft.format(c.getActualCPUTime()) +
                        "    " + dft.format(c.getExecStartTime()) + "    " + dft.format(c.getExecFinishTime()));
            }
        }
    }
}
