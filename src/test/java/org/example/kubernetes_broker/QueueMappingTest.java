package org.example.kubernetes_broker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the Volcano queue mapping logic in Live_Kubernetes_Broker_Ex.
 * These tests verify that cloudlet classType values map to the correct queue names,
 * ensuring the proportion plugin receives the right queue assignments.
 */
class QueueMappingTest {

    @Test
    void classType1MapsToHighPriority() {
        assertEquals("high-priority", Live_Kubernetes_Broker_Ex.QUEUE_NAMES.get(1));
    }

    @Test
    void classType2MapsToBatch() {
        assertEquals("batch", Live_Kubernetes_Broker_Ex.QUEUE_NAMES.get(2));
    }

    @Test
    void classType0ReturnsNull() {
        // classType 0 means "default queue" — no explicit queue field is sent
        assertNull(Live_Kubernetes_Broker_Ex.QUEUE_NAMES.get(0));
    }

    @Test
    void unknownClassTypeReturnsNull() {
        // Any unmapped classType should not produce a queue assignment
        assertNull(Live_Kubernetes_Broker_Ex.QUEUE_NAMES.get(99));
    }

    @Test
    void queueMapIsImmutable() {
        // The map should not be modifiable at runtime
        assertThrows(UnsupportedOperationException.class, () ->
            Live_Kubernetes_Broker_Ex.QUEUE_NAMES.put(3, "test")
        );
    }
}
