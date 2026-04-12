package org.example.metrics;

import org.example.kubernetes_broker.BatchDecisionResponse;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks per-pod scheduling latency and overall throughput for COUBES simulations.
 * Thread-safe for concurrent access from CloudSim event handlers.
 * 
 * Feature: coubes-next-phase
 * Requirements: 8.1, 8.2, 8.3, 8.4, 8.5, 8.6, 8.7, 8.9
 */
public class PerformanceMetrics {
    
    // Per-cloudlet submission timestamps
    private final Map<Integer, Instant> submissionTimestamps = new ConcurrentHashMap<>();
    
    // Recorded latencies in milliseconds
    private final List<Long> latenciesMs = new CopyOnWriteArrayList<>();
    
    // Throughput tracking
    private volatile Instant firstSubmission;
    private volatile Instant lastBinding;
    private final AtomicInteger totalScheduled = new AtomicInteger(0);
    
    /**
     * Records the submission timestamp for a cloudlet.
     * Call this immediately before sending the cloudlet to the adapter.
     * 
     * @param cloudletId the CloudSim cloudlet ID
     */
    public void recordSubmission(int cloudletId) {
        Instant now = Instant.now();
        submissionTimestamps.put(cloudletId, now);
        
        // Set first submission timestamp if this is the first call
        if (firstSubmission == null) {
            synchronized (this) {
                if (firstSubmission == null) {
                    firstSubmission = now;
                }
            }
        }
    }
    
    /**
     * Records scheduling decisions from a BatchDecision response.
     * Computes latency for each scheduled pod and updates throughput metrics.
     * 
     * @param response the BatchDecision response from the adapter
     */
    public void recordBatchDecision(BatchDecisionResponse response) {
        if (response == null || response.getScheduled() == null) {
            return;
        }
        
        for (BatchDecisionResponse.PodAssignment assignment : response.getScheduled()) {
            int podId = assignment.getPodId();
            Instant bindingTimestamp = assignment.getBindingTimestamp();
            
            if (bindingTimestamp == null) {
                continue;
            }
            
            Instant submissionTimestamp = submissionTimestamps.get(podId);
            if (submissionTimestamp != null) {
                long latencyMs = Duration.between(submissionTimestamp, bindingTimestamp).toMillis();
                latenciesMs.add(latencyMs);
                
                // Update last binding timestamp
                synchronized (this) {
                    if (lastBinding == null || bindingTimestamp.isAfter(lastBinding)) {
                        lastBinding = bindingTimestamp;
                    }
                }
                
                totalScheduled.incrementAndGet();
            }
        }
    }
    
    /**
     * Returns the arithmetic mean of all recorded scheduling latencies.
     * 
     * @return average latency in milliseconds, or 0.0 if no latencies recorded
     */
    public double getAverageLatencyMs() {
        if (latenciesMs.isEmpty()) {
            return 0.0;
        }
        
        long sum = 0;
        for (Long latency : latenciesMs) {
            sum += latency;
        }
        
        return (double) sum / latenciesMs.size();
    }
    
    /**
     * Returns the 99th-percentile scheduling latency.
     * 
     * @return P99 latency in milliseconds, or 0.0 if fewer than 100 samples
     */
    public double getP99LatencyMs() {
        if (latenciesMs.size() < 100) {
            return 0.0;
        }
        
        List<Long> sorted = new CopyOnWriteArrayList<>(latenciesMs);
        sorted.sort(Long::compareTo);
        
        int p99Index = (int) Math.ceil(sorted.size() * 0.99) - 1;
        return sorted.get(p99Index);
    }
    
    /**
     * Returns the overall pod scheduling throughput.
     * 
     * @return throughput in pods per second, or 0.0 if elapsed time is zero
     */
    public double getThroughputPodsPerSec() {
        if (firstSubmission == null || lastBinding == null) {
            return 0.0;
        }
        
        long elapsedSeconds = Duration.between(firstSubmission, lastBinding).getSeconds();
        if (elapsedSeconds == 0) {
            return 0.0;
        }
        
        return (double) totalScheduled.get() / elapsedSeconds;
    }
    
    /**
     * Resets all recorded metrics to initial state.
     * Enables reuse of the same PerformanceMetrics instance across multiple simulation runs.
     */
    public void reset() {
        submissionTimestamps.clear();
        latenciesMs.clear();
        firstSubmission = null;
        lastBinding = null;
        totalScheduled.set(0);
    }
}
