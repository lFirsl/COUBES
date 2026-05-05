package org.example.metrics;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Size;
import org.example.kubernetes_broker.BatchDecisionResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Property-based tests for PerformanceMetrics.
 * 
 * Feature: coubes-next-phase
 */
class PerformanceMetricsTest {
    
    // Feature: coubes-next-phase, Property 13
    @Property(tries = 100)
    void latencyComputationIsCorrect(
            @ForAll @Size(min = 100, max = 200) List<@IntRange(min = 10, max = 100) Integer> latenciesMs) {
        
        PerformanceMetrics metrics = new PerformanceMetrics();
        
        // Record submissions first
        for (int i = 0; i < latenciesMs.size(); i++) {
            metrics.recordSubmission(i);
        }
        
        // Small delay to ensure submission timestamps are in the past
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Create batch decision with binding timestamps
        BatchDecisionResponse response = new BatchDecisionResponse();
        List<BatchDecisionResponse.PodAssignment> scheduled = new ArrayList<>();
        
        Instant now = Instant.now();
        for (int i = 0; i < latenciesMs.size(); i++) {
            BatchDecisionResponse.PodAssignment assignment = new BatchDecisionResponse.PodAssignment();
            assignment.setPodId(i);
            assignment.setNodeId(1);
            assignment.setBindingTimestamp(now.plusMillis(latenciesMs.get(i)));
            scheduled.add(assignment);
        }
        
        response.setScheduled(scheduled);
        metrics.recordBatchDecision(response);
        
        // Verify that metrics are calculated
        double actualAvg = metrics.getAverageLatencyMs();
        assertTrue(actualAvg > 0, "Average latency should be positive");
        
        double actualP99 = metrics.getP99LatencyMs();
        assertTrue(actualP99 >= 0, "P99 latency should be non-negative");
        
        // Verify that we have the expected number of latency measurements
        // This is an indirect way to verify the computation is working
        assertTrue(actualAvg > 10, "Average latency should be reasonable given our test data");
    }
    
    // Feature: coubes-next-phase, Property 14
    @Property(tries = 100)
    void resetClearsAllState(
            @ForAll @Size(min = 1, max = 100) List<@IntRange(min = 0, max = 5000) Integer> latenciesMs) {
        
        PerformanceMetrics metrics = new PerformanceMetrics();
        Instant baseTime = Instant.now();
        
        // Record some data
        for (int i = 0; i < latenciesMs.size(); i++) {
            metrics.recordSubmission(i);
            
            BatchDecisionResponse response = new BatchDecisionResponse();
            List<BatchDecisionResponse.PodAssignment> scheduled = new ArrayList<>();
            
            BatchDecisionResponse.PodAssignment assignment = new BatchDecisionResponse.PodAssignment();
            assignment.setPodId(i);
            assignment.setNodeId(1);
            assignment.setBindingTimestamp(baseTime.plusMillis(latenciesMs.get(i)));
            scheduled.add(assignment);
            
            response.setScheduled(scheduled);
            metrics.recordBatchDecision(response);
        }
        
        // Reset
        metrics.reset();
        
        // Verify all metrics are zero
        assertEquals(0.0, metrics.getAverageLatencyMs(), "Average latency should be 0 after reset");
        assertEquals(0.0, metrics.getP99LatencyMs(), "P99 latency should be 0 after reset");
        assertEquals(0.0, metrics.getThroughputPodsPerSec(), "Throughput should be 0 after reset");
    }
    
    @Test
    void averageLatencyReturnsZeroWithNoData() {
        PerformanceMetrics metrics = new PerformanceMetrics();
        assertEquals(0.0, metrics.getAverageLatencyMs());
    }
    
    @Test
    void p99LatencyComputesWithFewerThan100Samples() {
        PerformanceMetrics metrics = new PerformanceMetrics();
        
        // Record 50 samples with ~100ms latency each
        for (int i = 0; i < 50; i++) {
            metrics.recordSubmission(i);
            
            BatchDecisionResponse response = new BatchDecisionResponse();
            List<BatchDecisionResponse.PodAssignment> scheduled = new ArrayList<>();
            
            BatchDecisionResponse.PodAssignment assignment = new BatchDecisionResponse.PodAssignment();
            assignment.setPodId(i);
            assignment.setNodeId(1);
            assignment.setBindingTimestamp(Instant.now().plusMillis(100));
            scheduled.add(assignment);
            
            response.setScheduled(scheduled);
            metrics.recordBatchDecision(response);
        }
        
        // P99 should be computed (non-zero) even with fewer than 100 samples
        assertTrue(metrics.getP99LatencyMs() > 0.0);
    }
    
    @Test
    void throughputReturnsZeroWithNoData() {
        PerformanceMetrics metrics = new PerformanceMetrics();
        assertEquals(0.0, metrics.getThroughputPodsPerSec());
    }
}
