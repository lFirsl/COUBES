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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedList;
import java.util.List;

/**
 * Stress-test variant of Fragmentation_Test.
 * 10 VMs, 50 cloudlets submitted in two waves:
 *   - Wave 1 (t=0):  35 short cloudlets (1 PE each, length 40000)
 *   - Wave 2 (t=50): 15 long cloudlets  (1 PE each, length 400000)
 *
 * With only 10 VMs and 50 cloudlets, the adapter must queue 40 pending pods
 * and reschedule them as VMs free up. This exercises the full rescheduling loop.
 */
public class Fragmentation_Test_Large {

    private static List<Cloudlet> cloudletList;
    private static List<Vm> vmlist;

    private static List<Vm> createVM(int userId, int vms, int idShift) {
        LinkedList<Vm> list = new LinkedList<>();
        long size = 10000;
        int ram = 512;
        int mips = 250;
        long bw = 1000;
        int pesNumber = 5;
        String vmm = "Xen";
        for (int i = 0; i < vms; i++) {
            list.add(new PowerVmCustom(idShift + i, userId, mips, pesNumber, ram, bw, size, 0, vmm,
                    new CloudletSchedulerTimeShared(), 1, -1));
        }
        return list;
    }

    private static List<Cloudlet> createCloudlet(int userId, int cloudlets, int length, int pes, int idShift) {
        LinkedList<Cloudlet> list = new LinkedList<>();
        long fileSize = 300;
        long outputSize = 300;
        UtilizationModel utilizationModel = new UtilizationModelFull();
        for (int i = 0; i < cloudlets; i++) {
            Cloudlet c = new Cloudlet(idShift + i, length, pes, fileSize, outputSize,
                    utilizationModel, utilizationModel, utilizationModel);
            c.setUserId(userId);
            list.add(c);
        }
        return list;
    }

    public static void main(String[] args) {
        Log.println("Starting Fragmentation_Test_Large...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom datacenter0 = createDatacenter("Datacenter_0");

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            List<Vm> vmlistAll = new ArrayList<>();
            List<Cloudlet> cloudletListAll = new ArrayList<>();

            // 10 VMs — intentionally fewer than cloudlets to exercise the rescheduling loop
            vmlist = createVM(brokerId, 10, 0);
            vmlistAll.addAll(vmlist);
            broker.submitGuestList(vmlist);

            // Wave 1: 35 short cloudlets at t=0 (1 PE, length 40000)
            cloudletList = createCloudlet(brokerId, 35, 40000, 1, 0);
            cloudletListAll.addAll(cloudletList);
            broker.submitCloudletList(cloudletList);

            // Wave 2: 15 long cloudlets at t=50 (1 PE, length 400000)
            cloudletList = createCloudlet(brokerId, 15, 400000, 1, 100);
            cloudletListAll.addAll(cloudletList);
            broker.submitCloudletList(cloudletList, 50);

            CloudSim.resumeSimulation();

            SimulationMetrics metrics = new SimulationMetrics(datacenter0, vmlistAll);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            Log.printConcat("Broker has a lifetime of: ", broker.getLifeLength());

            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != 50) {
                throw new RuntimeException("Expected 50 cloudlets to complete but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall());

            broker.sendResetRequestToControlPlane();
            Log.println("Fragmentation_Test_Large finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        int mips = 250;
        int ram = 16384;
        long storage = 1000000;
        int bw = 10000;
        // 2 hosts × 5 PEs = capacity for 10 VMs (each VM requests 5 PEs at 250 MIPS)
        for (int i = 0; i < 2; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 5; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(ram),
                    new BwProvisionerSimple(bw),
                    storage,
                    peList,
                    new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }

        DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);

        PowerDatacenterCustom datacenter = null;
        try {
            datacenter = new PowerDatacenterCustom(name, characteristics,
                    new VmAllocationPolicySimple(hostList), new LinkedList<>(), 1, true);
            datacenter.setDisableMigrations(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return datacenter;
    }

    private static void printCloudletList(List<Cloudlet> list) {
        String indent = "    ";
        Log.println();
        Log.println("========== OUTPUT ==========");
        Log.println("Cloudlet ID" + indent + "STATUS" + indent +
                "DC ID" + indent + "VM ID" + indent + "Time" + indent + "Start" + indent + "Finish");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet c : list) {
            Log.print(indent + c.getCloudletId() + indent + indent);
            if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                Log.println("SUCCESS" + indent + c.getResourceId() + indent + c.getGuestId() +
                        indent + dft.format(c.getActualCPUTime()) +
                        indent + dft.format(c.getExecStartTime()) +
                        indent + dft.format(c.getExecFinishTime()));
            }
        }
    }
}
