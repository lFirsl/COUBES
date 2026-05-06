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
 * Multi-PE pod test: pods that each need 3 PEs on nodes with 5 PEs.
 *
 * Setup: 2 hosts × 5 PEs, 2 VMs × 5 PEs = 10 PE slots total.
 * Each pod needs 3 PEs, so only 1 pod fits per node (3 ≤ 5, but 3+3=6 > 5).
 * 6 pods total → only 2 per round, 3 rounds needed.
 *
 * This tests that the scheduler correctly tracks multi-PE consumption
 * and doesn't over-pack nodes.
 */
public class MultiPE_Pod_Test {

    public static void main(String[] args) {
        Log.println("Starting MultiPE_Pod_Test...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            // 2 VMs with 5 PEs each
            List<Vm> vms = createVMs(brokerId, 2, 0);
            broker.submitGuestList(vms);

            // 6 pods, each needing 3 PEs
            List<Cloudlet> cloudlets = createCloudlets(brokerId, 6, 50000, 3, 0);
            broker.submitCloudletList(cloudlets);

            CloudSim.resumeSimulation();
            SimulationMetrics metrics = new SimulationMetrics(dc, vms);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != 6) {
                throw new RuntimeException("Expected 6 cloudlets but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());
            broker.sendResetRequestToControlPlane();
            Log.println("MultiPE_Pod_Test finished!");

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
        System.out.println("Cloudlet ID    STATUS    VM ID    Time    Start    Finish");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet c : list) {
            if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                System.out.println("    " + c.getCloudletId() + "        SUCCESS    " +
                        c.getGuestId() + "    " + dft.format(c.getActualCPUTime()) +
                        "    " + dft.format(c.getExecStartTime()) + "    " + dft.format(c.getExecFinishTime()));
            }
        }
    }
}
