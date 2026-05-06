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
 * 5-wave fragmentation test.
 * 5 VMs, 50 cloudlets submitted across 5 waves of 10 each.
 * Waves arrive every 50 simulated seconds with increasing cloudlet lengths,
 * forcing the rescheduling loop to fire repeatedly as VMs free up.
 *
 * Wave schedule:
 *   Wave 1 (t=0):   10 cloudlets, length=40000
 *   Wave 2 (t=50):  10 cloudlets, length=80000
 *   Wave 3 (t=100): 10 cloudlets, length=120000
 *   Wave 4 (t=150): 10 cloudlets, length=160000
 *   Wave 5 (t=200): 10 cloudlets, length=200000
 *
 * ID ranges: wave N uses idShift = (N-1)*100, so IDs never collide.
 */
public class Fragmentation_Test_5Wave {

    private static List<Vm> createVM(int userId, int vms, int idShift) {
        LinkedList<Vm> list = new LinkedList<>();
        for (int i = 0; i < vms; i++) {
            list.add(new PowerVmCustom(idShift + i, userId, 250, 5, 512, 1000, 10000, 0, "Xen",
                    new CloudletSchedulerTimeShared(), 1, -1));
        }
        return list;
    }

    private static List<Cloudlet> createCloudlet(int userId, int count, int length, int idShift) {
        LinkedList<Cloudlet> list = new LinkedList<>();
        UtilizationModel um = new UtilizationModelFull();
        for (int i = 0; i < count; i++) {
            Cloudlet c = new Cloudlet(idShift + i, length, 1, 300, 300, um, um, um);
            c.setUserId(userId);
            list.add(c);
        }
        return list;
    }

    public static void main(String[] args) {
        Log.println("Starting Fragmentation_Test_5Wave...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom datacenter0 = createDatacenter("Datacenter_0");
            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            // 5 VMs — fewer than total cloudlets, forcing rescheduling across all waves
            List<Vm> vms = createVM(brokerId, 5, 0);
            broker.submitGuestList(vms);

            // 5 waves × 10 cloudlets, submitted with 50s delays between waves
            int totalCloudlets = 0;
            for (int wave = 0; wave < 5; wave++) {
                int length = 40000 * (wave + 1);
                double delay = wave * 50.0;
                List<Cloudlet> batch = createCloudlet(brokerId, 10, length, wave * 100);
                if (wave == 0) {
                    broker.submitCloudletList(batch);
                } else {
                    broker.submitCloudletList(batch, delay);
                }
                totalCloudlets += batch.size();
            }

            CloudSim.resumeSimulation();

            SimulationMetrics metrics = new SimulationMetrics(datacenter0, vms);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != totalCloudlets) {
                throw new RuntimeException("Expected " + totalCloudlets + " cloudlets but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());
            broker.sendResetRequestToControlPlane();
            Log.println("Fragmentation_Test_5Wave finished!");
        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static PowerDatacenterCustom createDatacenter(String name) {
        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < 5; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(250)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(16384),
                    new BwProvisionerSimple(10000),
                    1000000, peList,
                    new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));
        }
        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = null;
        try {
            dc = new PowerDatacenterCustom(name, chars,
                    new VmAllocationPolicySimple(hostList), new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return dc;
    }

    private static void printCloudletList(List<Cloudlet> list) {
        String indent = "    ";
        Log.println();
        Log.println("========== OUTPUT ==========");
        Log.println("Cloudlet ID" + indent + "STATUS" + indent + "VM ID" + indent + "Time" + indent + "Start" + indent + "Finish");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet c : list) {
            if (c.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                Log.println(indent + c.getCloudletId() + indent + "SUCCESS" + indent + c.getGuestId() +
                        indent + dft.format(c.getActualCPUTime()) +
                        indent + dft.format(c.getExecStartTime()) +
                        indent + dft.format(c.getExecFinishTime()));
            }
        }
    }
}
