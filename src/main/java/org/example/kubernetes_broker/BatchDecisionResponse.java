package org.example.kubernetes_broker;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/**
 * DTO for BatchDecision response from the adapter.
 * Matches the JSON schema defined in the batch protocol.
 * 
 * Feature: coubes-next-phase
 * Requirements: 5.4
 */
public class BatchDecisionResponse {
    
    @JsonProperty("scheduled")
    private List<PodAssignment> scheduled;
    
    @JsonProperty("unschedulable")
    private List<PodFailure> unschedulable;

    @JsonProperty("decisionDurationMs")
    private long decisionDurationMs;
    
    public List<PodAssignment> getScheduled() {
        return scheduled;
    }
    
    public void setScheduled(List<PodAssignment> scheduled) {
        this.scheduled = scheduled;
    }
    
    public List<PodFailure> getUnschedulable() {
        return unschedulable;
    }
    
    public void setUnschedulable(List<PodFailure> unschedulable) {
        this.unschedulable = unschedulable;
    }

    public long getDecisionDurationMs() {
        return decisionDurationMs;
    }
    
    /**
     * Represents a successful pod-to-node assignment.
     */
    public static class PodAssignment {
        
        @JsonProperty("podId")
        private int podId;
        
        @JsonProperty("nodeId")
        private int nodeId;
        
        @JsonProperty("bindingTimestamp")
        private Instant bindingTimestamp;
        
        public int getPodId() {
            return podId;
        }
        
        public void setPodId(int podId) {
            this.podId = podId;
        }
        
        public int getNodeId() {
            return nodeId;
        }
        
        public void setNodeId(int nodeId) {
            this.nodeId = nodeId;
        }
        
        public Instant getBindingTimestamp() {
            return bindingTimestamp;
        }
        
        public void setBindingTimestamp(Instant bindingTimestamp) {
            this.bindingTimestamp = bindingTimestamp;
        }
    }
    
    /**
     * Represents a pod that could not be scheduled.
     */
    public static class PodFailure {
        
        @JsonProperty("podId")
        private int podId;
        
        @JsonProperty("reason")
        private String reason;
        
        public int getPodId() {
            return podId;
        }
        
        public void setPodId(int podId) {
            this.podId = podId;
        }
        
        public String getReason() {
            return reason;
        }
        
        public void setReason(String reason) {
            this.reason = reason;
        }
    }
}
