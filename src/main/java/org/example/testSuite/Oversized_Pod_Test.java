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
 * Oversized pod test: one pod needs more PEs than any node has.
 *
 * Setup: 2 hosts × 4 PEs, 2 VMs × 4 PEs.
 * 3 pods: two need 2 PEs (should schedule fine), one needs 6 PEs (permanently unschedulable).
 *
 * The simulation should complete with only 2 cloudlets finished.
 * The oversized pod should remain pending forever and the simulation should
 * still terminate gracefully (not hang).
 *
 * NOTE: In full mode, the real scheduler takes time to evaluate the oversized pod
 * each rescheduling round, which can trigger the hang detector. Use --test-mode.
 */
public class Oversized_Pod_Test {

    public static void main(String[] args) {
        Log.println("Starting Oversized_Pod_Test...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            // 2 VMs with 4 PEs each
            List<Vm> vms = createVMs(brokerId, 2, 0);
            broker.submitGuestList(vms);

            // 2 normal pods (2 PEs each) + 1 oversized pod (6 PEs)
            List<Cloudlet> cloudlets = new ArrayList<>();
            UtilizationModel um = new UtilizationModelFull();
            cloudlets.add(new Cloudlet(0, 50000, 2, 300, 300, um, um, um));
            cloudlets.get(0).setUserId(brokerId);
            cloudlets.add(new Cloudlet(1, 50000, 2, 300, 300, um, um, um));
            cloudlets.get(1).setUserId(brokerId);
            cloudlets.add(new Cloudlet(2, 50000, 6, 300, 300, um, um, um));
            cloudlets.get(2).setUserId(brokerId);

            broker.submitCloudletList(cloudlets);

            CloudSim.resumeSimulation();
            SimulationMetrics metrics = new SimulationMetrics(dc, vms);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());

            // We expect only 2 to complete — the oversized pod can never be scheduled
            if (results.size() == 2) {
                Log.println("Oversized_Pod_Test PASSED: 2 of 3 cloudlets completed, oversized pod correctly stayed pending.");
            } else if (results.size() == 3) {
                throw new RuntimeException("BUG: All 3 cloudlets completed — the oversized pod should not have been schedulable!");
            } else {
                throw new RuntimeException("Expected 2 completed cloudlets but got " + results.size());
            }

            broker.sendResetRequestToControlPlane();
            Log.println("Oversized_Pod_Test finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static List<Vm> createVMs(int userId, int count, int idShift) {
        List<Vm> list = new LinkedList<>();
        for (int i = 0; i < count; i++) {
            list.add(new PowerVmCustom(idShift + i, userId, 250, 4, 512, 1000, 10000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 1, -1));
        }
        return list;
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 4; p++) {
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
