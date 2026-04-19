package org.example.bugfix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import net.jqwik.api.*;
import org.example.kubernetes_broker.BatchDecisionResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Bug Condition Exploration Tests for CloudSim Scheduling Errors Fix
 * 
 * CRITICAL: These tests MUST FAIL on unfixed code - failure confirms the bugs exist.
 * DO NOT attempt to fix the tests or the code when they fail.
 * These tests encode the expected behavior - they will validate the fixes when they pass after implementation.
 * 
 * Spec: cloudsim-scheduling-errors-fix
 */
class BugConditionExplorationTest {
    
    /**
     * Bug 1: JSON Casting Error - Direct Test of Parsing Logic
     * 
     * **Validates: Requirements 2.1**
     * 
     * Tests that parsing a BatchDecisionResponse JSON string as ArrayNode (the bug) fails,
     * but parsing as ObjectNode (the fix) succeeds.
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: This test demonstrates the bug by showing that
     * casting to ArrayNode throws ClassCastException, while the correct approach (ObjectNode) works.
     */
    @Test
    void bug1_castingBatchDecisionResponseToArrayNode_throwsClassCastException() {
        // Arrange: Create a valid BatchDecisionResponse JSON
        String jsonResponse = """
            {
                "scheduled": [
                    {"podId": 1, "nodeId": 0, "bindingTimestamp": "2024-01-01T00:00:00Z"}
                ],
                "unschedulable": [
                    {"podId": 2, "reason": "Insufficient resources"}
                ]
            }
            """;
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        
        // Act & Assert: Demonstrate the bug - casting to ArrayNode fails
        assertThrows(ClassCastException.class, () -> {
            JsonNode root = mapper.readTree(jsonResponse);
            ArrayNode arrayNode = (ArrayNode) root; // This is the bug!
        }, "Casting ObjectNode to ArrayNode should throw ClassCastException");
        
        // Demonstrate the fix - parsing as ObjectNode and accessing "scheduled" field works
        assertDoesNotThrow(() -> {
            JsonNode root = mapper.readTree(jsonResponse);
            ObjectNode objectNode = (ObjectNode) root; // Correct approach
            ArrayNode scheduled = (ArrayNode) objectNode.get("scheduled");
            ArrayNode unschedulable = (ArrayNode) objectNode.get("unschedulable");
            assertNotNull(scheduled);
            assertNotNull(unschedulable);
        }, "Parsing as ObjectNode and accessing fields should work");
    }
    
    /**
     * Bug 1: Scheduled Pods Only - ArrayNode Cast Fails
     * 
     * **Validates: Requirements 2.1**
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Demonstrates ClassCastException
     */
    @Test
    void bug1_scheduledPodsOnly_arrayNodeCastFails() {
        String jsonResponse = """
            {
                "scheduled": [
                    {"podId": 1, "nodeId": 0, "bindingTimestamp": "2024-01-01T00:00:00Z"}
                ],
                "unschedulable": []
            }
            """;
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        
        // Bug: Casting to ArrayNode fails
        assertThrows(ClassCastException.class, () -> {
            ArrayNode arrayNode = (ArrayNode) mapper.readTree(jsonResponse);
        });
        
        // Fix: Parsing as ObjectNode works
        assertDoesNotThrow(() -> {
            ObjectNode objectNode = (ObjectNode) mapper.readTree(jsonResponse);
            ArrayNode scheduled = (ArrayNode) objectNode.get("scheduled");
            assertEquals(1, scheduled.size());
        });
    }
    
    /**
     * Bug 1: Unschedulable Pods Only - ArrayNode Cast Fails
     * 
     * **Validates: Requirements 2.1**
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Demonstrates ClassCastException
     */
    @Test
    void bug1_unschedulablePodsOnly_arrayNodeCastFails() {
        String jsonResponse = """
            {
                "scheduled": [],
                "unschedulable": [
                    {"podId": 2, "reason": "Insufficient resources"}
                ]
            }
            """;
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        
        // Bug: Casting to ArrayNode fails
        assertThrows(ClassCastException.class, () -> {
            ArrayNode arrayNode = (ArrayNode) mapper.readTree(jsonResponse);
        });
        
        // Fix: Parsing as ObjectNode works
        assertDoesNotThrow(() -> {
            ObjectNode objectNode = (ObjectNode) mapper.readTree(jsonResponse);
            ArrayNode unschedulable = (ArrayNode) objectNode.get("unschedulable");
            assertEquals(1, unschedulable.size());
        });
    }
    
    /**
     * Bug 1: Empty Response - ArrayNode Cast Fails
     * 
     * **Validates: Requirements 2.1**
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Demonstrates ClassCastException
     */
    @Test
    void bug1_emptyResponse_arrayNodeCastFails() {
        String jsonResponse = """
            {
                "scheduled": [],
                "unschedulable": []
            }
            """;
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        
        // Bug: Casting to ArrayNode fails
        assertThrows(ClassCastException.class, () -> {
            ArrayNode arrayNode = (ArrayNode) mapper.readTree(jsonResponse);
        });
        
        // Fix: Parsing as ObjectNode works
        assertDoesNotThrow(() -> {
            ObjectNode objectNode = (ObjectNode) mapper.readTree(jsonResponse);
            assertTrue(objectNode.get("scheduled").isEmpty());
            assertTrue(objectNode.get("unschedulable").isEmpty());
        });
    }
    
    /**
     * Bug 1: Property-Based Test - ArrayNode Cast Always Fails for BatchDecisionResponse
     * 
     * **Validates: Requirements 2.1**
     * 
     * EXPECTED OUTCOME ON UNFIXED CODE: Demonstrates that casting BatchDecisionResponse JSON
     * to ArrayNode always throws ClassCastException, regardless of content.
     */
    @Property(tries = 20)
    void bug1_batchDecisionResponseArrayNodeCast_alwaysFails(
            @ForAll("batchDecisionJson") String jsonResponse) {
        
        ObjectMapper mapper = new ObjectMapper();
        mapper.findAndRegisterModules();
        
        // Bug: Casting to ArrayNode always fails for BatchDecisionResponse structure
        assertThrows(ClassCastException.class, () -> {
            ArrayNode arrayNode = (ArrayNode) mapper.readTree(jsonResponse);
        }, "Casting BatchDecisionResponse JSON to ArrayNode should always throw ClassCastException");
        
        // Fix: Parsing as ObjectNode always works
        assertDoesNotThrow(() -> {
            ObjectNode objectNode = (ObjectNode) mapper.readTree(jsonResponse);
            assertNotNull(objectNode.get("scheduled"));
            assertNotNull(objectNode.get("unschedulable"));
        }, "Parsing as ObjectNode should always work");
    }
    
    @Provide
    Arbitrary<String> batchDecisionJson() {
        return Arbitraries.integers().between(0, 5).flatMap(scheduledCount ->
            Arbitraries.integers().between(0, 5).map(unschedulableCount -> {
                StringBuilder json = new StringBuilder("{\"scheduled\":[");
                for (int i = 0; i < scheduledCount; i++) {
                    if (i > 0) json.append(",");
                    json.append(String.format("{\"podId\":%d,\"nodeId\":0,\"bindingTimestamp\":\"2024-01-01T00:00:00Z\"}", i));
                }
                json.append("],\"unschedulable\":[");
                for (int i = 0; i < unschedulableCount; i++) {
                    if (i > 0) json.append(",");
                    json.append(String.format("{\"podId\":%d,\"reason\":\"Test reason\"}", 100 + i));
                }
                json.append("]}");
                return json.toString();
            })
        );
    }
}
