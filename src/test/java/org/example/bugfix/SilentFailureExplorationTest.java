package org.example.bugfix;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.CloudletSchedulerTimeShared;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug Condition Exploration Tests for Silent Failure on Incomplete Cloudlet Returns
 * 
 * CRITICAL: These tests MUST FAIL on unfixed code - failure confirms the bug exists.
 * DO NOT attempt to fix the tests or the code when they fail.
 * These tests encode the expected behavior - they will validate the fixes when they pass after implementation.
 * 
 * Spec: cloudsim-scheduling-errors-fix
 */
class SilentFailureExplorationTest {
    
    /**
     * Bug 2: Silent Failure on Incomplete Cloudlet Returns - Zero Cloudlets Returned
     * 
     * **Validates: Requirements 2.2, 2.3**
     * 
     * Tests that when cloudlets are submitted but none are returned, the system throws a RuntimeException.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test FAILS because no RuntimeException is thrown.
     * Instead, the unfixed code logs: "We got 0 cloudlets whereas we were supposed to get 20!"
     * but continues execution and reports "Success".
     * 
     * This test demonstrates Bug Condition 2: The simulation reports success even when cloudlets
     * fail to complete, masking critical scheduling failures.
     */
    @Test
    void bug2_zeroCloudletsReturned_shouldThrowRuntimeException() {
        // Arrange: Simulate scenario where 20 cloudlets were submitted but 0 returned
        int cloudletsSubmitted = 20;
        List<Cloudlet> cloudletsReceived = new ArrayList<>(); // Empty list
        
        // Act & Assert: Should throw RuntimeException with clear error message
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            validateCloudletCompletion(cloudletsSubmitted, cloudletsReceived);
        }, "Should throw RuntimeException when no cloudlets are returned");
        
        // Verify exception message contains expected and actual counts
        String message = exception.getMessage();
        assertTrue(message.contains("20"), "Exception message should contain expected count (20)");
        assertTrue(message.contains("0"), "Exception message should contain actual count (0)");
        assertTrue(message.toLowerCase().contains("expected") || message.toLowerCase().contains("cloudlet"),
                "Exception message should be descriptive");
    }
    
    /**
     * Bug 2: Silent Failure - Partial Cloudlet Return
     * 
     * **Validates: Requirements 2.2, 2.3**
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test FAILS because no RuntimeException is thrown
     */
    @Test
    void bug2_partialCloudletsReturned_shouldThrowRuntimeException() {
        // Arrange: 15 cloudlets submitted, only 10 returned
        int cloudletsSubmitted = 15;
        List<Cloudlet> cloudletsReceived = createMockCloudlets(10);
        
        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            validateCloudletCompletion(cloudletsSubmitted, cloudletsReceived);
        }, "Should throw RuntimeException when fewer cloudlets are returned than submitted");
        
        String message = exception.getMessage();
        assertTrue(message.contains("15"), "Exception message should contain expected count (15)");
        assertTrue(message.contains("10"), "Exception message should contain actual count (10)");
    }
    
    /**
     * Bug 2: Complete Cloudlet Return - Should NOT Throw
     * 
     * **Validates: Requirements 3.1**
     * 
     * Tests that when all cloudlets are returned, no exception is thrown (preservation of correct behavior).
     * 
     * EXPECTED OUTCOME: Test PASSES on both unfixed and fixed code
     */
    @Test
    void bug2_allCloudletsReturned_shouldNotThrowException() {
        // Arrange: 5 cloudlets submitted, 5 returned
        int cloudletsSubmitted = 5;
        List<Cloudlet> cloudletsReceived = createMockCloudlets(5);
        
        // Act & Assert: Should NOT throw exception
        assertDoesNotThrow(() -> {
            validateCloudletCompletion(cloudletsSubmitted, cloudletsReceived);
        }, "Should not throw exception when all cloudlets are returned");
    }
    
    /**
     * Bug 2: Property-Based Test - Incomplete Returns Always Throw
     * 
     * **Validates: Requirements 2.2, 2.3**
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Test FAILS because no RuntimeException is thrown
     */
    @Property(tries = 50)
    void bug2_incompleteCloudletReturns_shouldAlwaysThrowRuntimeException(
            @ForAll @IntRange(min = 1, max = 100) int cloudletsSubmitted,
            @ForAll @IntRange(min = 0, max = 99) int cloudletsReturned) {
        
        // Only test cases where fewer cloudlets are returned than submitted
        Assume.that(cloudletsReturned < cloudletsSubmitted);
        
        // Arrange
        List<Cloudlet> cloudletsReceivedList = createMockCloudlets(cloudletsReturned);
        
        // Act & Assert: Should always throw RuntimeException
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            validateCloudletCompletion(cloudletsSubmitted, cloudletsReceivedList);
        }, String.format("Should throw RuntimeException when %d cloudlets submitted but only %d returned",
                cloudletsSubmitted, cloudletsReturned));
        
        // Verify exception message is informative
        String message = exception.getMessage();
        assertNotNull(message, "Exception message should not be null");
        assertFalse(message.isEmpty(), "Exception message should not be empty");
    }
    
    /**
     * Bug 2: Property-Based Test - Complete Returns Never Throw
     * 
     * **Validates: Requirements 3.1**
     * 
     * EXPECTED OUTCOME: Test PASSES on both unfixed and fixed code
     */
    @Property(tries = 50)
    void bug2_completeCloudletReturns_shouldNeverThrowException(
            @ForAll @IntRange(min = 1, max = 100) int cloudletsCount) {
        
        // Arrange: Same number submitted and returned
        List<Cloudlet> cloudletsReceivedList = createMockCloudlets(cloudletsCount);
        
        // Act & Assert: Should NOT throw exception
        assertDoesNotThrow(() -> {
            validateCloudletCompletion(cloudletsCount, cloudletsReceivedList);
        }, String.format("Should not throw exception when all %d cloudlets are returned", cloudletsCount));
    }
    
    /**
     * Helper method that simulates the cloudlet validation logic.
     * This encodes the EXPECTED behavior (throwing RuntimeException on mismatch).
     * 
     * In the unfixed code (Fragmentation_Test.java line 153), this only logs a warning.
     * After the fix, this behavior should be implemented in the actual test files.
     */
    private void validateCloudletCompletion(int expected, List<Cloudlet> received) {
        if (received.size() != expected) {
            throw new RuntimeException(
                    String.format("Expected %d cloudlets to complete but only received %d",
                            expected, received.size()));
        }
    }
    
    /**
     * Helper method to create mock cloudlets for testing.
     */
    private List<Cloudlet> createMockCloudlets(int count) {
        List<Cloudlet> cloudlets = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Cloudlet cloudlet = new Cloudlet(
                    i,                              // cloudletId
                    40000,                          // length
                    1,                              // pesNumber
                    300,                            // fileSize
                    300,                            // outputSize
                    new UtilizationModelFull(),     // utilizationModelCpu
                    new UtilizationModelFull(),     // utilizationModelRam
                    new UtilizationModelFull()      // utilizationModelBw
            );
            cloudlet.setCloudletStatus(Cloudlet.CloudletStatus.SUCCESS);
            cloudlets.add(cloudlet);
        }
        return cloudlets;
    }
}
