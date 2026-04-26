package org.example.metrics;

import org.cloudbus.cloudsim.Datacenter;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.PowerDatacenter;
import org.example.kubernetes_broker.PowerDatacenterCustom;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
}
