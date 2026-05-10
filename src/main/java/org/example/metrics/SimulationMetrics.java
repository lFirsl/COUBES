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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SimulationMetrics {
    private Instant wallStart;
    private Instant wallEnd;
    private PowerDatacenterCustom powerDatacenter;
    private List<Vm> vms;
    private PerformanceMetrics performanceMetrics;
    private List<Cloudlet> completedCloudlets;
    private Map<Integer, Double> arrivalTimes;

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

    public void setCompletedCloudlets(List<Cloudlet> cloudlets, Map<Integer, Double> arrivals) {
        this.completedCloudlets = cloudlets;
        this.arrivalTimes = arrivals;
    }

    public long getWallClockMillis() {
        return Duration.between(wallStart, wallEnd).toMillis();
    }

    public long getWallClockSeconds() {
        return Duration.between(wallStart, wallEnd).toSeconds();
    }

    public void printSummary(Double simTime,double throughput) {
        printSummary(simTime, throughput, 0.0);
    }

    public void printSummary(Double simTime, double effectiveThroughput, double peakThroughput) {
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
        if(effectiveThroughput > 0){
            System.out.println("Effective Throughput: " + effectiveThroughput + " pods/s");
        }
        else System.out.println("Effective Throughput: N/A");
        if(peakThroughput > 0){
            System.out.println("Peak Throughput: " + peakThroughput + " pods/s");
        }
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

        // Always write structured results file
        writeResultsFile(simTime, effectiveThroughput, peakThroughput, completedCloudlets, arrivalTimes);
    }

    public void printSummary(Double simTime){
        printSummary(simTime,-1);
    }

    public void printSummary(Double simTime, double throughput, int actualRounds, int expectedMinRounds) {
        printSummary(simTime, throughput, 0.0);
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
            // Grep-friendly summary line
            System.out.printf("    [metric] %s avg_turnaround: %.2f%n", queueName, avgTurnaround);
        }
        System.out.println("-----------------------------");
    }

    /**
     * Writes a structured JSON results file with all metrics.
     * The file is written to results/latest_run.json (overwritten each run).
     */
    public void writeResultsFile(double simTime, double effectiveThroughput, double peakThroughput, List<Cloudlet> completedCloudlets, Map<Integer, Double> arrivalTimes) {
        Map<String, String> metrics = new LinkedHashMap<>();
        // scenario_passed: true if at least one cloudlet succeeded, or if sim ran meaningfully
        boolean passed;
        if (completedCloudlets != null) {
            passed = completedCloudlets.stream()
                    .anyMatch(c -> c.getStatus() == Cloudlet.CloudletStatus.SUCCESS);
        } else {
            passed = simTime > 1.0; // simulation ran for more than 1 second
        }
        metrics.put("scenario_passed", String.valueOf(passed));
        metrics.put("simulated_time_s", String.format("%.2f", simTime));
        metrics.put("wall_clock_ms", String.valueOf(getWallClockMillis()));
        if (powerDatacenter != null) {
            metrics.put("energy_wh", String.format("%.2f", powerDatacenter.getPower() / 3600.0));
            metrics.put("consolidation_ratio", String.valueOf(powerDatacenter.getConsolidationAverage(simTime)));
        }
        if (effectiveThroughput > 0) metrics.put("effective_throughput_pods_per_s", String.valueOf(effectiveThroughput));
        if (peakThroughput > 0) metrics.put("peak_throughput_pods_per_s", String.valueOf(peakThroughput));
        if (completedCloudlets != null) {
            metrics.put("cloudlets_completed", String.valueOf(completedCloudlets.size()));
            // Per-queue turnaround
            Map<Integer, List<Cloudlet>> byQueue = completedCloudlets.stream()
                    .collect(Collectors.groupingBy(Cloudlet::getClassType));
            for (var entry : byQueue.entrySet()) {
                String queueName = Live_Kubernetes_Broker_Ex.QUEUE_NAMES.get(entry.getKey());
                if (queueName == null) continue;
                double avg = entry.getValue().stream().mapToDouble(c -> {
                    double arrival = arrivalTimes != null && arrivalTimes.containsKey(c.getCloudletId())
                            ? arrivalTimes.get(c.getCloudletId()) : c.getSubmissionTime();
                    return c.getExecFinishTime() - arrival;
                }).average().orElse(0);
                metrics.put(queueName.replace("-", "_") + "_avg_turnaround_s", String.format("%.2f", avg));
            }
        }
        if (performanceMetrics != null) {
            metrics.put("avg_scheduling_latency_ms", String.format("%.2f", performanceMetrics.getAverageLatencyMs()));
            metrics.put("p99_scheduling_latency_ms", String.format("%.2f", performanceMetrics.getP99LatencyMs()));
        }

        // Write as simple JSON
        StringBuilder sb = new StringBuilder("{\n");
        var entries = metrics.entrySet().stream().toList();
        for (int i = 0; i < entries.size(); i++) {
            var e = entries.get(i);
            sb.append("  \"").append(e.getKey()).append("\": \"").append(e.getValue()).append("\"");
            if (i < entries.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("}\n");

        try {
            Path dir = Path.of("results");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("latest_run.json"), sb.toString());
        } catch (IOException e) {
            System.err.println("WARNING: Could not write results/latest_run.json: " + e.getMessage());
        }
    }
}
