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
 * Scheduler scalability test — measures scheduling latency at two pod-to-node ratios.
 *
 * Phase 1: 20 pods on 5 nodes  (4:1 ratio)
 * Phase 2: 100 pods on 5 nodes (20:1 ratio)
 *
 * Each phase runs as a separate simulation so latency metrics are isolated.
 * This lets you compare how different schedulers scale under increasing load.
 */
public class Scheduler_Scalability_Test {

    private static final int NUM_NODES = 5;
    private static final int PES_PER_NODE = 5;
    private static final int MIPS = 250;

    public static void main(String[] args) {
        Log.println("Starting Scheduler_Scalability_Test...");

        try {
            // Warmup: run a small simulation to pay JVM class-loading and JIT costs
            System.out.println("--- JVM warmup (results discarded) ---");
            runPhase("Warmup", 10, 0);

            // Phase 1: 4:1 ratio (20 pods, 5 nodes)
            PerformanceMetrics phase1Perf = runPhase("Phase 1 (4:1)", 20, 0);

            // Phase 2: 20:1 ratio (100 pods, 5 nodes)
            PerformanceMetrics phase2Perf = runPhase("Phase 2 (20:1)", 100, 0);

            // Compare results
            System.out.println();
            System.out.println("========== SCALABILITY COMPARISON ==========");
            System.out.printf("Phase 1 (4:1)  — Avg latency: %.2f ms | P99: %.2f ms | Throughput: %.2f pods/s%n",
                    phase1Perf.getAverageLatencyMs(),
                    phase1Perf.getP99LatencyMs(),
                    phase1Perf.getThroughputPodsPerSec());
            System.out.printf("Phase 2 (20:1) — Avg latency: %.2f ms | P99: %.2f ms | Throughput: %.2f pods/s%n",
                    phase2Perf.getAverageLatencyMs(),
                    phase2Perf.getP99LatencyMs(),
                    phase2Perf.getThroughputPodsPerSec());

            double avgRatio = phase1Perf.getAverageLatencyMs() > 0
                    ? phase2Perf.getAverageLatencyMs() / phase1Perf.getAverageLatencyMs()
                    : Double.NaN;
            System.out.printf("Latency ratio (20:1 / 4:1): %.2fx%n", avgRatio);
            System.out.println("=============================================");

            Log.println("Scheduler_Scalability_Test finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.println("Simulation terminated due to an unexpected error");
        }
    }

    /**
     * Runs a single simulation phase with the given number of pods on NUM_NODES nodes.
     * Returns the PerformanceMetrics captured during the phase.
     */
    private static PerformanceMetrics runPhase(String phaseName, int numPods, int cloudletIdShift) throws Exception {
        System.out.println();
        System.out.println("----- " + phaseName + ": " + numPods + " pods on " + NUM_NODES + " nodes -----");

        CloudSim.init(2, Calendar.getInstance(), false);

        PowerDatacenterCustom datacenter = createDatacenter("Datacenter_0");
        datacenter.setLogLevel(PowerDatacenterCustom.LogLevel.QUIET);

        Live_Kubernetes_Broker_Ex broker = new Live_Kubernetes_Broker_Ex("Broker_0", -1);
        int brokerId = broker.getId();

        PerformanceMetrics perf = new PerformanceMetrics();
        broker.setPerformanceMetrics(perf);

        List<Vm> vms = createVMs(brokerId, NUM_NODES, 0);
        broker.submitGuestList(vms);

        List<Cloudlet> cloudlets = createCloudlets(brokerId, numPods, 50000, 1, cloudletIdShift);
        broker.submitCloudletList(cloudlets);

        CloudSim.resumeSimulation();

        SimulationMetrics simMetrics = new SimulationMetrics(datacenter, vms, perf);
        simMetrics.startWallClock();
        double lastClock = CloudSim.startSimulation();

        List<Cloudlet> results = broker.getCloudletReceivedList();
        CloudSim.stopSimulation();
        simMetrics.stopWallClock();

        if (results.size() != numPods) {
            System.out.println("WARNING: Expected " + numPods + " cloudlets but got " + results.size());
        }

        simMetrics.printSummary(lastClock, broker.tpOverall());
        broker.sendResetRequestToControlPlane();

        return perf;
    }

    private static List<Vm> createVMs(int userId, int count, int idShift) {
        List<Vm> list = new LinkedList<>();
        for (int i = 0; i < count; i++) {
            list.add(new PowerVmCustom(
                    idShift + i, userId, MIPS, PES_PER_NODE, 512, 1000, 10000,
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
        for (int i = 0; i < NUM_NODES; i++) {
            List<Pe> peList = new ArrayList<>();
            for (int p = 0; p < PES_PER_NODE; p++) {
                peList.add(new Pe(p, new PeProvisionerSimple(MIPS)));
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
