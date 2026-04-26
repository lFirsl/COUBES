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
 * Mixed Affinity Test — verifies that hard and soft constraints can coexist
 * on the same pod, with hard constraints taking precedence.
 *
 * 4 VMs × 4 PEs. 4 pods, each with:
 *   - Hard anti-affinity "spread"         → MUST be on different nodes
 *   - Soft affinity "prefer-together"     → PREFER same node (but can't)
 *
 * Expected: hard anti-affinity wins — all 4 pods on 4 distinct nodes.
 * The soft affinity is best-effort and gets overridden.
 *
 * Run with: bash run_test.sh org.example.testSuite.Mixed_Affinity_Test
 * (Full mode only.)
 */
public class Mixed_Affinity_Test {
    public static Live_Kubernetes_Broker_Ex broker;

    public static void main(String[] args) {
        Log.println("Starting Mixed_Affinity_Test...");

        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom dc = createDatacenter("Datacenter_0");
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            List<Vm> vmlist = createVM(brokerId, 4, 0);
            List<Cloudlet> cloudlets = new ArrayList<>();

            UtilizationModel util = new UtilizationModelFull();

            // 4 pods: hard anti-affinity (must spread) + soft affinity (prefer colocate)
            for (int i = 0; i < 4; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(i, 40000, 1, 300, 300,
                        util, util, util, 0, Collections.emptyMap(),
                        "prefer-together", "spread",
                        false, true);  // soft affinity, hard anti-affinity
                cl.setUserId(brokerId);
                cloudlets.add(cl);
            }

            broker.submitGuestList(vmlist);
            broker.submitCloudletList(cloudlets);

            CloudSim.resumeSimulation();

            SimulationMetrics metrics = new SimulationMetrics(dc, vmlist);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != 4) {
                throw new RuntimeException("Expected 4 cloudlets but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.getRoundCount(), 1);

            // Verify: hard anti-affinity should force all 4 pods onto distinct VMs
            Set<Integer> vms = new HashSet<>();
            for (Cloudlet cl : results) vms.add(cl.getGuestId());

            if (vms.size() == 4) {
                Log.println("PASS: Hard anti-affinity respected — all 4 pods on distinct VMs: " + vms);
                Log.println("PASS: Soft affinity correctly overridden by hard anti-affinity");
            } else {
                Log.println("FAIL: Expected 4 distinct VMs, got " + vms.size() + ": " + vms);
            }

            broker.sendResetRequestToControlPlane();
            Log.println("Mixed_Affinity_Test finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.println("The simulation has been terminated due to an unexpected error");
        }
    }

    private static List<Vm> createVM(int userId, int vms, int idShift) {
        LinkedList<Vm> list = new LinkedList<>();
        for (int i = 0; i < vms; i++) {
            list.add(new PowerVmCustom(idShift + i, userId, 250, 4, 1024, 1000, 10000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 500, -1));
        }
        return list;
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        for (int x = 0; x < 4; x++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 4; p++) peList.add(new Pe(p, new PeProvisionerSimple(250)));
            hostList.add(new PowerHost(x,
                    new RamProvisionerSimple(16384), new BwProvisionerSimple(10000),
                    1000000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }
        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = null;
        try {
            dc = new PowerDatacenterCustom(name, chars,
                    new VmAllocationPolicySimple(hostList), new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
        } catch (Exception e) { e.printStackTrace(); }
        return dc;
    }

    private static void printCloudletList(List<Cloudlet> list) {
        String indent = "    ";
        Log.println();
        Log.println("========== OUTPUT ==========");
        Log.println("Cloudlet ID" + indent + "STATUS" + indent +
                "Data center ID" + indent + "VM ID" + indent + indent +
                "Time" + indent + "Start Time" + indent + "Finish Time");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet cl : list) {
            Log.print(indent + cl.getCloudletId() + indent + indent);
            if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                Log.print("SUCCESS");
                Log.println(indent + indent + cl.getResourceId() + indent + indent + indent +
                        cl.getGuestId() + indent + indent + indent +
                        dft.format(cl.getActualCPUTime()) + indent + indent +
                        dft.format(cl.getExecStartTime()) + indent + indent + indent +
                        dft.format(cl.getExecFinishTime()));
            }
        }
    }
}
