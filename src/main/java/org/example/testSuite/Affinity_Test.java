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
 * Affinity / Anti-Affinity Test — verifies that pod affinity and anti-affinity
 * rules are respected by the real kube-scheduler (full mode only).
 *
 * Cluster: 4 VMs × 4 PEs × 1024 MB RAM.
 *
 * Pods 0-2: anti-affinity group "spread" → must land on DIFFERENT nodes.
 * Pods 3-5: affinity group "colocate"    → must land on the SAME node.
 *
 * After scheduling, we verify:
 *   - Pods 0, 1, 2 are each on a distinct VM
 *   - Pods 3, 4, 5 are all on the same VM
 *
 * Run with: bash run_test.sh org.example.testSuite.Affinity_Test
 * (Full mode only — test mode scheduler ignores affinity rules.)
 */
public class Affinity_Test {
    public static Live_Kubernetes_Broker_Ex broker;

    public static void main(String[] args) {
        Log.println("Starting Affinity_Test...");

        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom datacenter0 = createDatacenter("Datacenter_0");
            datacenter0.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            List<Vm> vmlist = createVM(brokerId, 4, 0);
            List<Cloudlet> allCloudlets = new ArrayList<>();

            UtilizationModel util = new UtilizationModelFull();

            // Pods 0-2: anti-affinity group "spread" — each must go to a different node
            for (int i = 0; i < 3; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(i, 40000, 1, 300, 300,
                        util, util, util, 0, Collections.emptyMap(), null, "spread");
                cl.setUserId(brokerId);
                allCloudlets.add(cl);
            }

            // Pods 3-5: affinity group "colocate" — all must go to the same node
            for (int i = 3; i < 6; i++) {
                CoubesCloudlet cl = new CoubesCloudlet(i, 40000, 1, 300, 300,
                        util, util, util, 0, Collections.emptyMap(), "colocate", null);
                cl.setUserId(brokerId);
                allCloudlets.add(cl);
            }

            broker.submitGuestList(vmlist);
            broker.submitCloudletList(allCloudlets);

            CloudSim.resumeSimulation();

            SimulationMetrics metrics = new SimulationMetrics(datacenter0, vmlist);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != 6) {
                throw new RuntimeException("Expected 6 cloudlets but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.getRoundCount(), 1);

            // Verify anti-affinity: pods 0,1,2 on distinct VMs
            Set<Integer> spreadVms = new HashSet<>();
            for (Cloudlet cl : results) {
                if (cl.getCloudletId() < 3) spreadVms.add(cl.getGuestId());
            }
            if (spreadVms.size() != 3) {
                Log.println("FAIL: Anti-affinity violated — spread pods on " + spreadVms.size() + " VMs (expected 3)");
            } else {
                Log.println("PASS: Anti-affinity respected — spread pods on 3 distinct VMs: " + spreadVms);
            }

            // Verify affinity: pods 3,4,5 on same VM
            Set<Integer> colocateVms = new HashSet<>();
            for (Cloudlet cl : results) {
                if (cl.getCloudletId() >= 3) colocateVms.add(cl.getGuestId());
            }
            if (colocateVms.size() != 1) {
                Log.println("FAIL: Affinity violated — colocate pods on " + colocateVms.size() + " VMs (expected 1)");
            } else {
                Log.println("PASS: Affinity respected — colocate pods all on VM " + colocateVms.iterator().next());
            }

            broker.sendResetRequestToControlPlane();
            Log.println("Affinity_Test finished!");
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
        int hostId = 0;
        for (int x = 0; x < 4; x++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 4; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(250)));
            }
            hostList.add(new PowerHost(hostId++,
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
