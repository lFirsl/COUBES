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
 * Minimal sanity check: 1 host, 1 VM, 1 cloudlet.
 * Verifies the entire pipeline works with the smallest possible inputs.
 */
public class Single_Pod_Test {

    public static void main(String[] args) {
        Log.println("Starting Single_Pod_Test...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            // 1 host with 2 PEs
            List<Host> hostList = new ArrayList<>();
            List<Pe> peList = new ArrayList<>();
            peList.add(new Pe(0, new PeProvisionerSimple(250)));
            peList.add(new Pe(1, new PeProvisionerSimple(250)));
            hostList.add(new PowerHost(0,
                    new RamProvisionerSimple(16384), new BwProvisionerSimple(10000),
                    1000000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.1)));

            DatacenterCharacteristics chars = new DatacenterCharacteristics(
                    "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
            PowerDatacenterCustom dc = new PowerDatacenterCustom(
                    "Datacenter_0", chars, new VmAllocationPolicySimple(hostList),
                    new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
            dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            // 1 VM
            List<Vm> vms = new ArrayList<>();
            vms.add(new PowerVmCustom(0, brokerId, 250, 2, 512, 1000, 10000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 1, -1));
            broker.submitGuestList(vms);

            // 1 cloudlet
            UtilizationModel um = new UtilizationModelFull();
            List<Cloudlet> cloudlets = new ArrayList<>();
            Cloudlet c = new Cloudlet(0, 50000, 1, 300, 300, um, um, um);
            c.setUserId(brokerId);
            cloudlets.add(c);
            broker.submitCloudletList(cloudlets);

            CloudSim.resumeSimulation();
            SimulationMetrics metrics = new SimulationMetrics(dc, vms);
            metrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            metrics.stopWallClock();

            if (results.size() != 1) {
                throw new RuntimeException("Expected 1 cloudlet but received " + results.size());
            }

            printCloudletList(results);
            metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());
            broker.sendResetRequestToControlPlane();
            Log.println("Single_Pod_Test finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static void printCloudletList(List<Cloudlet> list) {
        System.out.println("\n========== OUTPUT ==========");
        DecimalFormat dft = new DecimalFormat("###.##");
        for (Cloudlet cl : list) {
            if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) {
                System.out.println("    " + cl.getCloudletId() + "    SUCCESS    VM " +
                        cl.getGuestId() + "    " + dft.format(cl.getActualCPUTime()) +
                        "    " + dft.format(cl.getExecStartTime()) + "    " + dft.format(cl.getExecFinishTime()));
            }
        }
    }
}
