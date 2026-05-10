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
 * Scalability Test — 50 VMs, 10 waves of 500 pods each (5000 total).
 * All homogeneous. Purpose: validate COUBES handles large scenarios.
 */
public class Scalability_Test {

    public static void main(String[] args) throws Exception {
        CloudSim.init(2, Calendar.getInstance(), false);

        int numHosts = 50;
        int pesPerHost = 4;
        int mips = 250;

        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < pesPerHost; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(mips)));
            }
            hostList.add(new PowerHost(i,
                    new RamProvisionerSimple(4096), new BwProvisionerSimple(10000),
                    100000, peList, new VmSchedulerTimeShared(peList),
                    new PowerModelLinear(500, 0.30)));
        }

        DatacenterCharacteristics chars = new DatacenterCharacteristics(
                "x86", "Linux", "Xen", hostList, 10.0, 3.0, 0.05, 0.1, 0.1);
        PowerDatacenterCustom dc = new PowerDatacenterCustom(
                "Datacenter_0", chars, new VmAllocationPolicySimple(hostList),
                new LinkedList<>(), 1, true);
        dc.setDisableMigrations(true);
        dc.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

        Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0");
        int brokerId = broker.getId();

        List<Vm> vmList = new ArrayList<>();
        for (int i = 0; i < numHosts; i++) {
            vmList.add(new PowerVmCustom(i, brokerId, mips, pesPerHost,
                    4096, 10000, 100000, 0, "Xen",
                    new CloudletSchedulerTimeShared(), 1, i));
        }
        broker.submitGuestList(vmList);

        UtilizationModel full = new UtilizationModelFull();
        int id = 0;

        // 10 waves of 500 pods, each 1 PE, length=10000 MI (40s on 250 MIPS)
        // Waves arrive every 30s
        for (int wave = 0; wave < 10; wave++) {
            List<Cloudlet> waveList = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                waveList.add(new Cloudlet(id++, 10000, 1, 300, 300, full, full, full));
            }
            if (wave == 0) {
                broker.submitCloudletList(waveList);
            } else {
                broker.submitCloudletList(waveList, wave * 30.0);
            }
        }

        // Run
        CloudSim.resumeSimulation();
        SimulationMetrics metrics = new SimulationMetrics(dc, vmList);
        metrics.startWallClock();
        double lastClock = CloudSim.startSimulation();
        CloudSim.stopSimulation();
        metrics.stopWallClock();

        // Results
        List<Cloudlet> results = broker.getCloudletReceivedList();
        Log.printLine("========== Scalability_Test Results ==========");

        int succeeded = 0, failed = 0;
        for (Cloudlet cl : results) {
            if (cl.getStatus() == Cloudlet.CloudletStatus.SUCCESS) succeeded++;
            else failed++;
        }
        Log.printlnConcat("Completed: ", succeeded, " succeeded, ", failed, " failed (of 5000 total)");
        Log.printLine("");
        metrics.setCompletedCloudlets(results, broker.getCloudletArrivalTimes());
        metrics.printSummary(lastClock, broker.tpOverall(), broker.tpPeak());

        try { broker.sendResetRequestToControlPlane(); } catch (Exception ignored) {}
        Log.printLine("\nScalability_Test finished!");
    }
}
