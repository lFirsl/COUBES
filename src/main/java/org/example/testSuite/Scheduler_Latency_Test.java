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
import org.example.metrics.PerformanceMetrics;
import org.example.metrics.SimulationMetrics;

import java.util.*;

/**
 * Scheduler latency test.
 *
 * Phase 1 (warmup): 20 pods on 5 nodes (4:1) submitted at t=0.
 * Phase 2 (load):  100 pods on 5 nodes (20:1) submitted at t=15.
 *
 * Scheduling latency (submission → binding) is measured across the entire run.
 */
public class Scheduler_Latency_Test {

    public static void main(String[] args) {
        Log.println("Starting Scheduler_Latency_Test...");
        try {
            CloudSim.init(2, Calendar.getInstance(), false);

            PowerDatacenterCustom datacenter = createDatacenter("Datacenter_0");
            Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
            int brokerId = broker.getId();

            PerformanceMetrics perf = new PerformanceMetrics();
            broker.setPerformanceMetrics(perf);

            // 5 VMs, 20 PEs each — enough for 100 concurrent cloudlets
            List<Vm> vms = createVMs(brokerId, 5, 0);
            broker.submitGuestList(vms);

            // Wave 1: 20 cloudlets at t=0 (warmup)
            List<Cloudlet> wave1 = createCloudlets(brokerId, 20, 50000, 1, 0);
            broker.submitCloudletList(wave1);

            // Wave 2: 100 cloudlets at t=15 (load increase)
            List<Cloudlet> wave2 = createCloudlets(brokerId, 100, 50000, 1, 100);
            broker.submitCloudletList(wave2, 15);

            CloudSim.resumeSimulation();

            SimulationMetrics simMetrics = new SimulationMetrics(datacenter, vms, perf);
            simMetrics.startWallClock();
            double lastClock = CloudSim.startSimulation();

            List<Cloudlet> results = broker.getCloudletReceivedList();
            CloudSim.stopSimulation();
            simMetrics.stopWallClock();

            if (results.size() != 120) {
                System.out.println("WARNING: Expected 120 cloudlets but got " + results.size());
            }

            simMetrics.printSummary(lastClock, broker.tpOverall());
            broker.sendResetRequestToControlPlane();
            Log.println("Scheduler_Latency_Test finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    private static List<Vm> createVMs(int userId, int count, int idShift) {
        List<Vm> list = new LinkedList<>();
        for (int i = 0; i < count; i++) {
            list.add(new PowerVmCustom(
                    idShift + i, userId, 250, 5, 512, 1000, 10000,
                    0, "Xen", new CloudletSchedulerTimeShared(), 500, -1));
        }
        return list;
    }

    private static List<Cloudlet> createCloudlets(int userId, int count, long length, int pes, int idShift) {
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
        for (int i = 0; i < 5; i++) {
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
        try {
            PowerDatacenterCustom dc = new PowerDatacenterCustom(
                    name, chars, new VmAllocationPolicySimple(hostList),
                    new LinkedList<>(), 1, true);
            dc.setDisableMigrations(true);
            return dc;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
