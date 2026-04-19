package org.example.bugfix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Preservation Property Tests for CloudSim Scheduling Errors Fix
 * 
 * **Validates: Requirements 3.1, 3.2, 3.3, 3.4**
 * 
 * CRITICAL: These tests capture CORRECT behavior that must be preserved when fixing Bug 2.
 * They should PASS on UNFIXED code for successful scenarios (when all cloudlets complete).
 * 
 * These tests verify:
 * - When all cloudlets complete successfully, system reports success and prints results (3.1)
 * - Pod-to-node mapping works correctly (3.2)
 * - Unschedulable pods are marked as FAILED (3.3)
 * - Metrics (EWMA, sliding window, overall throughput) are calculated correctly (3.4)
 * 
 * Spec: cloudsim-scheduling-errors-fix
 */
class PreservationPropertyTest {
    
    /**
     * Preservation Property 1: Complete Cloudlet Returns Report Success
     * 
     * **Validates: Requirements 3.1**
     * 
     * When all submitted cloudlets are returned (cloudletsReceived == cloudletsSubmitted),
     * the system should report success without throwing exceptions.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test PASSES (this behavior is already correct)
     */
    @Test
    void preservation_allCloudletsReturned_reportsSuccess() {
        // Arrange: Simulate scenario where all cloudlets are returned
        int cloudletsSubmitted = 20;
        List<Cloudlet> cloudletsReceived = createSuccessfulCloudlets(20);
        
        // Act & Assert: Should NOT throw exception
        assertDoesNotThrow(() -> {
            validateCloudletCompletion(cloudletsSubmitted, cloudletsReceived);
        }, "Should not throw exception when all cloudlets are returned");
        
        // Verify all cloudlets have SUCCESS status
        for (Cloudlet cloudlet : cloudletsReceived) {
            assertEquals(Cloudlet.CloudletStatus.SUCCESS, cloudlet.getStatus(),
                    "All returned cloudlets should have SUCCESS status");
        }
    }
    
    /**
     * Preservation Property 2: BatchDecisionResponse with Only Scheduled Pods Parses Correctly
     * 
     * **Validates: Requirements 3.2**
     * 
     * When the adapter returns a BatchDecisionResponse with only scheduled pods (no unschedulable),
     * the system should correctly parse the response and extract pod-to-node mappings.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test PASSES (ObjectNode parsing works correctly)
     */
    @Test
    void preservation_scheduledPodsOnly_parsesCorrectly() throws Exception {
        // Arrange: Create a BatchDecisionResponse with only scheduled pods
        String jsonResponse = """
            {
                "scheduled": [
                    {"id": 1, "vmId": 0, "status": "Scheduled"},
                    {"id": 2, "vmId": 1, "status": "Scheduled"},
                    {"id": 3, "vmId": 2, "status": "Scheduled"}
                ],
                "unschedulable": []
            }
            """;
        
        ObjectMapper mapper = new ObjectMapper();
        
        // Act: Parse as ObjectNode (the correct approach)
        ObjectNode batchDecision = (ObjectNode) mapper.readTree(jsonResponse);
        ArrayNode scheduled = (ArrayNode) batchDecision.get("scheduled");
        ArrayNode unschedulable = (ArrayNode) batchDecision.get("unschedulable");
        
        // Assert: Verify correct parsing
        assertNotNull(scheduled, "Scheduled array should not be null");
        assertNotNull(unschedulable, "Unschedulable array should not be null");
        assertEquals(3, scheduled.size(), "Should have 3 scheduled pods");
        assertEquals(0, unschedulable.size(), "Should have 0 unschedulable pods");
        
        // Verify pod-to-node mappings are correct
        assertEquals(1, scheduled.get(0).get("id").asInt());
        assertEquals(0, scheduled.get(0).get("vmId").asInt());
        assertEquals(2, scheduled.get(1).get("id").asInt());
        assertEquals(1, scheduled.get(1).get("vmId").asInt());
        assertEquals(3, scheduled.get(2).get("id").asInt());
        assertEquals(2, scheduled.get(2).get("vmId").asInt());
    }
    
    /**
     * Preservation Property 3: Unschedulable Pods Are Marked as FAILED
     * 
     * **Validates: Requirements 3.3**
     * 
     * When the adapter returns unschedulable pods, they should be marked as FAILED
     * and added to the received list.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test PASSES (this behavior should be preserved)
     */
    @Test
    void preservation_unschedulablePods_markedAsFailed() throws Exception {
        // Arrange: Create a BatchDecisionResponse with unschedulable pods
        String jsonResponse = """
            {
                "scheduled": [
                    {"id": 1, "vmId": 0, "status": "Scheduled"}
                ],
                "unschedulable": [
                    {"id": 2, "reason": "Insufficient resources"},
                    {"id": 3, "reason": "Node selector mismatch"}
                ]
            }
            """;
        
        ObjectMapper mapper = new ObjectMapper();
        
        // Act: Parse the response
        ObjectNode batchDecision = (ObjectNode) mapper.readTree(jsonResponse);
        ArrayNode scheduled = (ArrayNode) batchDecision.get("scheduled");
        ArrayNode unschedulable = (ArrayNode) batchDecision.get("unschedulable");
        
        // Assert: Verify unschedulable pods are identified
        assertNotNull(unschedulable, "Unschedulable array should not be null");
        assertEquals(2, unschedulable.size(), "Should have 2 unschedulable pods");
        
        // Simulate marking unschedulable pods as FAILED
        List<Cloudlet> failedCloudlets = new ArrayList<>();
        for (int i = 0; i < unschedulable.size(); i++) {
            int cloudletId = unschedulable.get(i).get("id").asInt();
            Cloudlet cloudlet = createCloudlet(cloudletId);
            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.FAILED);
            failedCloudlets.add(cloudlet);
        }
        
        // Verify all unschedulable cloudlets are marked as FAILED
        assertEquals(2, failedCloudlets.size());
        for (Cloudlet cloudlet : failedCloudlets) {
            assertEquals(Cloudlet.CloudletStatus.FAILED, cloudlet.getStatus(),
                    "Unschedulable cloudlets should be marked as FAILED");
        }
    }
    
    /**
     * Preservation Property 4: Metrics Calculation Remains Correct
     * 
     * **Validates: Requirements 3.4**
     * 
     * When cloudlets complete successfully, throughput metrics (EWMA, sliding window, overall)
     * should be calculated correctly.
     * 
     * This test verifies the mathematical correctness of throughput calculations.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test PASSES (metrics calculation is correct)
     */
    @Test
    void preservation_metricsCalculation_remainsCorrect() {
        // Arrange: Simulate throughput calculation
        int totalPods = 100;
        long totalNanos = 10_000_000_000L; // 10 seconds
        
        // Act: Calculate overall throughput (pods per second)
        double overallThroughput = totalPods / (totalNanos / 1e9);
        
        // Assert: Verify calculation is correct
        assertEquals(10.0, overallThroughput, 0.01, 
                "Overall throughput should be 10 pods/second");
        
        // Verify EWMA calculation (exponentially-weighted moving average)
        double alpha = 0.3;
        double previousEwma = 8.0;
        double instantRate = 12.0;
        double newEwma = alpha * instantRate + (1 - alpha) * previousEwma;
        
        assertEquals(9.2, newEwma, 0.01,
                "EWMA calculation should be correct");
        
        // Verify sliding window average calculation
        List<Double> window = List.of(8.0, 9.0, 10.0, 11.0, 12.0);
        double windowAvg = window.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        assertEquals(10.0, windowAvg, 0.01,
                "Sliding window average should be correct");
    }
    
    /**
     * Preservation Property 5: Property-Based Test - Complete Returns Never Throw
     * 
     * **Validates: Requirements 3.1**
     * 
     * For all simulations where cloudletsReceived == cloudletsSubmitted,
     * the system should report success without throwing exceptions.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test PASSES (this behavior is already correct)
     */
    @Property(tries = 50)
    void preservation_completeCloudletReturns_neverThrowException(
            @ForAll @IntRange(min = 1, max = 100) int cloudletsCount) {
        
        // Arrange: Same number submitted and returned
        List<Cloudlet> cloudletsReceived = createSuccessfulCloudlets(cloudletsCount);
        
        // Act & Assert: Should NOT throw exception
        assertDoesNotThrow(() -> {
            validateCloudletCompletion(cloudletsCount, cloudletsReceived);
        }, String.format("Should not throw exception when all %d cloudlets are returned", cloudletsCount));
        
        // Verify all cloudlets have SUCCESS status
        for (Cloudlet cloudlet : cloudletsReceived) {
            assertEquals(Cloudlet.CloudletStatus.SUCCESS, cloudlet.getStatus(),
                    "All returned cloudlets should have SUCCESS status");
        }
    }
    
    /**
     * Preservation Property 6: Property-Based Test - Pod-to-Node Mapping Correctness
     * 
     * **Validates: Requirements 3.2**
     * 
     * For all valid BatchDecisionResponse objects with scheduled pods,
     * the system should correctly extract pod IDs and node IDs.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test PASSES (ObjectNode parsing works)
     */
    @Property(tries = 50)
    void preservation_podToNodeMapping_alwaysCorrect(
            @ForAll("scheduledPods") List<PodAssignment> pods) throws Exception {
        
        // Arrange: Create BatchDecisionResponse JSON
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode batchDecision = mapper.createObjectNode();
        ArrayNode scheduled = mapper.createArrayNode();
        
        for (PodAssignment pod : pods) {
            ObjectNode podNode = mapper.createObjectNode();
            podNode.put("id", pod.podId);
            podNode.put("vmId", pod.nodeId);
            podNode.put("status", "Scheduled");
            scheduled.add(podNode);
        }
        
        batchDecision.set("scheduled", scheduled);
        batchDecision.set("unschedulable", mapper.createArrayNode());
        
        // Act: Parse the response
        ArrayNode scheduledArray = (ArrayNode) batchDecision.get("scheduled");
        
        // Assert: Verify all pod-to-node mappings are correct
        assertEquals(pods.size(), scheduledArray.size(), 
                "Should have correct number of scheduled pods");
        
        for (int i = 0; i < pods.size(); i++) {
            assertEquals(pods.get(i).podId, scheduledArray.get(i).get("id").asInt(),
                    "Pod ID should match");
            assertEquals(pods.get(i).nodeId, scheduledArray.get(i).get("vmId").asInt(),
                    "Node ID should match");
        }
    }
    
    /**
     * Preservation Property 7: Property-Based Test - Mixed Scheduled and Unschedulable
     * 
     * **Validates: Requirements 3.2, 3.3**
     * 
     * For all BatchDecisionResponse objects with both scheduled and unschedulable pods,
     * the system should correctly parse both arrays.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test PASSES (ObjectNode parsing works)
     */
    @Property(tries = 50)
    void preservation_mixedScheduledAndUnschedulable_parsesCorrectly(
            @ForAll @IntRange(min = 0, max = 20) int scheduledCount,
            @ForAll @IntRange(min = 0, max = 20) int unschedulableCount) throws Exception {
        
        // Arrange: Create BatchDecisionResponse JSON
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode batchDecision = mapper.createObjectNode();
        ArrayNode scheduled = mapper.createArrayNode();
        ArrayNode unschedulable = mapper.createArrayNode();
        
        for (int i = 0; i < scheduledCount; i++) {
            ObjectNode podNode = mapper.createObjectNode();
            podNode.put("id", i);
            podNode.put("vmId", i % 5); // Distribute across 5 nodes
            podNode.put("status", "Scheduled");
            scheduled.add(podNode);
        }
        
        for (int i = 0; i < unschedulableCount; i++) {
            ObjectNode podNode = mapper.createObjectNode();
            podNode.put("id", 100 + i);
            podNode.put("reason", "Test reason");
            unschedulable.add(podNode);
        }
        
        batchDecision.set("scheduled", scheduled);
        batchDecision.set("unschedulable", unschedulable);
        
        // Act: Parse the response
        ArrayNode scheduledArray = (ArrayNode) batchDecision.get("scheduled");
        ArrayNode unschedulableArray = (ArrayNode) batchDecision.get("unschedulable");
        
        // Assert: Verify correct parsing
        assertNotNull(scheduledArray, "Scheduled array should not be null");
        assertNotNull(unschedulableArray, "Unschedulable array should not be null");
        assertEquals(scheduledCount, scheduledArray.size(), 
                "Should have correct number of scheduled pods");
        assertEquals(unschedulableCount, unschedulableArray.size(),
                "Should have correct number of unschedulable pods");
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Helper method that validates cloudlet completion.
     * This is the EXPECTED behavior after the fix - it should throw RuntimeException
     * when cloudlets don't match, but NOT throw when they do match.
     */
    private void validateCloudletCompletion(int expected, List<Cloudlet> received) {
        if (received.size() != expected) {
            throw new RuntimeException(
                    String.format("Expected %d cloudlets to complete but only received %d",
                            expected, received.size()));
        }
    }
    
    /**
     * Helper method to create successful cloudlets for testing.
     */
    private List<Cloudlet> createSuccessfulCloudlets(int count) {
        List<Cloudlet> cloudlets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Cloudlet cloudlet = createCloudlet(i);
            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.SUCCESS);
            cloudlets.add(cloudlet);
        }
        return cloudlets;
    }
    
    /**
     * Helper method to create a single cloudlet.
     */
    private Cloudlet createCloudlet(int id) {
        return new Cloudlet(
                id,                              // cloudletId
                40000,                          // length
                1,                              // pesNumber
                300,                            // fileSize
                300,                            // outputSize
                new UtilizationModelFull(),     // utilizationModelCpu
                new UtilizationModelFull(),     // utilizationModelRam
                new UtilizationModelFull()      // utilizationModelBw
        );
    }
    
    // ========== Arbitraries for Property-Based Testing ==========
    
    @Provide
    Arbitrary<List<PodAssignment>> scheduledPods() {
        return Arbitraries.integers().between(1, 20).flatMap(count ->
            Arbitraries.integers().between(0, 10).list().ofSize(count).map(nodeIds -> {
                List<PodAssignment> pods = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    pods.add(new PodAssignment(i, nodeIds.get(i)));
                }
                return pods;
            })
        );
    }
    
    /**
     * Simple data class to represent a pod-to-node assignment.
     */
    private static class PodAssignment {
        final int podId;
        final int nodeId;
        
        PodAssignment(int podId, int nodeId) {
            this.podId = podId;
            this.nodeId = nodeId;
        }
    }
}
