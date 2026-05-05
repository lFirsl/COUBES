package org.example.metrics;

import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerDatacenter;
import org.example.kubernetes_broker.Live_Kubernetes_Broker_Ex;
import org.example.kubernetes_broker.PowerDatacenterCustom;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SimulationMetrics {
    private Instant wallStart;
    private Instant wallEnd;
    private PowerDatacenterCustom powerDatacenter;
    private List<Vm> vms;
    private PerformanceMetrics performanceMetrics;

    public SimulationMetrics(PowerDatacenterCustom pData, List<Vm> vms) {
        this(pData, vms, null);
    }

    public SimulationMetrics(PowerDatacenterCustom pData, List<Vm> vms, PerformanceMetrics perf) {
        powerDatacenter = pData;
        this.vms = vms;
        this.performanceMetrics = perf;
    }

    public void startWallClock() {
        wallStart = Instant.now();
    }

    public void stopWallClock() {
        wallEnd = Instant.now();
    }

    public long getWallClockMillis() {
        return Duration.between(wallStart, wallEnd).toMillis();
    }

    public long getWallClockSeconds() {
        return Duration.between(wallStart, wallEnd).toSeconds();
    }

    public void printSummary(Double simTime,double throughput) {
        System.out.println("----- Simulation Metrics -----");
        System.out.println("Simulated Time Elapsed: " + simTime + " units");
        System.out.println("Wall-clock Time Elapsed: " + getWallClockMillis() + " ms (" + getWallClockSeconds() + " s)");
        if(powerDatacenter != null) {
            List<Host> hosts = powerDatacenter.getHostList();

            int numberOfHosts = hosts.size();


            System.out.printf("Energy consumption: %.2f Wh%n", powerDatacenter.getPower() / (3600));
            System.out.println("Number of hosts: " + numberOfHosts);
            System.out.println("Time-weighted avg consolidation: " + powerDatacenter.getConsolidationAverage(simTime));

        }
        else{
            System.out.println("ERROR: No PowerDatacenter information provided!");
        }
        if(throughput > 0){
            System.out.println("Throughput: " + throughput);
        }
        else System.out.println("Throughput: N/A");
        if(vms != null) {
            int numberOfVms =vms.size();
            System.out.println("Number of VMs: " + numberOfVms);
        }
        else System.out.println("ERROR: No PowerVM information provided!");

        // Include PerformanceMetrics if provided
        if (performanceMetrics != null) {
            System.out.printf("Average Scheduling Latency: %.2f ms%n", performanceMetrics.getAverageLatencyMs());
            System.out.printf("P99 Scheduling Latency: %.2f ms%n", performanceMetrics.getP99LatencyMs());
            System.out.printf("Pod Throughput: %.2f pods/sec%n", performanceMetrics.getThroughputPodsPerSec());
        }

        System.out.println("--------------------------------");
    }

    public void printSummary(Double simTime){
        printSummary(simTime,-1);
    }

    public void printSummary(Double simTime, double throughput, int actualRounds, int expectedMinRounds) {
        printSummary(simTime, throughput);
        System.out.println("Scheduling rounds: " + actualRounds + " (minimum expected: " + expectedMinRounds + ")");
    }

    /**
     * Prints per-queue turnaround metrics for completed cloudlets.
     * Groups cloudlets by classType (mapped to queue names via {@link Live_Kubernetes_Broker_Ex#QUEUE_NAMES}).
     * For each queue, reports:
     *   - Number of cloudlets
     *   - Queue turnaround: (last finish) - (earliest submission)
     *   - Average per-cloudlet turnaround: mean of (finish - submission) per cloudlet
     *   - Average wait time: mean of (execStart - submission) per cloudlet
     */
    public static void printPerQueueMetrics(List<Cloudlet> completedCloudlets) {
        printPerQueueMetrics(completedCloudlets, null);
    }

    public static void printPerQueueMetrics(List<Cloudlet> completedCloudlets, Map<Integer, Double> arrivalTimes) {
        Map<Integer, List<Cloudlet>> byQueue = completedCloudlets.stream()
                .collect(Collectors.groupingBy(Cloudlet::getClassType));

        System.out.println("----- Per-Queue Metrics -----");
        for (var entry : byQueue.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            int classType = entry.getKey();
            List<Cloudlet> cloudlets = entry.getValue();
            String queueName = Live_Kubernetes_Broker_Ex.QUEUE_NAMES
                    .getOrDefault(classType, "default (classType=" + classType + ")");

            // Use arrival times if available, otherwise fall back to CloudSim submissionTime
            double earliestArrival = cloudlets.stream()
                    .mapToDouble(c -> arrivalTimes != null && arrivalTimes.containsKey(c.getCloudletId())
                            ? arrivalTimes.get(c.getCloudletId()) : c.getSubmissionTime())
                    .min().orElse(0);
            double latestFinish = cloudlets.stream()
                    .mapToDouble(Cloudlet::getExecFinishTime).max().orElse(0);
            double queueTurnaround = latestFinish - earliestArrival;

            double avgTurnaround = cloudlets.stream()
                    .mapToDouble(c -> {
                        double arrival = arrivalTimes != null && arrivalTimes.containsKey(c.getCloudletId())
                                ? arrivalTimes.get(c.getCloudletId()) : c.getSubmissionTime();
                        return c.getExecFinishTime() - arrival;
                    }).average().orElse(0);
            double avgWait = cloudlets.stream()
                    .mapToDouble(c -> {
                        double arrival = arrivalTimes != null && arrivalTimes.containsKey(c.getCloudletId())
                                ? arrivalTimes.get(c.getCloudletId()) : c.getSubmissionTime();
                        return c.getExecStartTime() - arrival;
                    }).average().orElse(0);

            System.out.printf("  Queue '%s': %d cloudlets%n", queueName, cloudlets.size());
            System.out.printf("    Queue turnaround:       %.1fs (arrived=%.1f, last finish=%.1f)%n",
                    queueTurnaround, earliestArrival, latestFinish);
            System.out.printf("    Avg cloudlet turnaround: %.1fs%n", avgTurnaround);
            System.out.printf("    Avg scheduling wait:     %.1fs%n", avgWait);
        }
        System.out.println("-----------------------------");
    }
}
