package scheduler

import (
	"context"
	"fmt"
	"testing"
	"time"

	"pgregory.net/rapid"
)

// Feature: coubes-next-phase, Property 9
// Property 9: BatchDecision completeness
// Validates: Requirements 4.1, 4.2, 4.3
//
// For any set of N pods submitted in a scheduling round, the resulting BatchDecision
// must contain exactly N entries across its scheduled and unschedulable lists combined,
// with each submitted pod ID appearing exactly once.
func TestProperty9_BatchDecisionCompleteness(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		// Generate a random number of pods (1 to 100)
		numPods := rapid.IntRange(1, 100).Draw(t, "numPods")

		// Generate unique random pod IDs using a map to track uniqueness
		podIDMap := make(map[int]bool)
		podIDs := make([]int, 0, numPods)
		for len(podIDs) < numPods {
			podID := rapid.IntRange(1, 10000).Draw(t, fmt.Sprintf("podID_%d", len(podIDs)))
			if !podIDMap[podID] {
				podIDMap[podID] = true
				podIDs = append(podIDs, podID)
			}
		}

		// Create a scheduling round
		round := NewSchedulingRound(5 * time.Second)

		// Begin the round
		err := round.Begin(numPods)
		if err != nil {
			t.Fatalf("Begin failed: %v", err)
		}

		// Randomly decide which pods succeed and which fail
		// Generate a random decision for each pod
		type podDecision struct {
			scheduled bool
			nodeID    int
		}
		decisions := make([]podDecision, numPods)
		for i := 0; i < numPods; i++ {
			scheduled := rapid.Bool().Draw(t, fmt.Sprintf("decision_%d", i))
			nodeID := 0
			if scheduled {
				nodeID = rapid.IntRange(1, 100).Draw(t, fmt.Sprintf("nodeID_%d", i))
			}
			decisions[i] = podDecision{
				scheduled: scheduled,
				nodeID:    nodeID,
			}
		}

		// Record all decisions in a goroutine
		go func() {
			for i := 0; i < numPods; i++ {
				podName := fmt.Sprintf("cspod-%d", podIDs[i])
				if decisions[i].scheduled {
					// Scheduled
					nodeName := fmt.Sprintf("csnode-%d", decisions[i].nodeID)
					round.RecordBinding(podName, nodeName)
				} else {
					// Failed
					round.RecordFailure(podName, "Unschedulable")
				}
			}
		}()

		// Wait for the batch decision
		ctx := context.Background()
		decision, err := round.Wait(ctx)
		if err != nil {
			t.Fatalf("Wait failed: %v", err)
		}

		// Verify completeness: exactly N entries total
		totalEntries := len(decision.Scheduled) + len(decision.Unschedulable)
		if totalEntries != numPods {
			t.Fatalf("Expected %d total entries, got %d (scheduled: %d, unschedulable: %d)",
				numPods, totalEntries, len(decision.Scheduled), len(decision.Unschedulable))
		}

		// Verify each pod ID appears exactly once
		seenPods := make(map[int]int) // podID -> count
		for _, assignment := range decision.Scheduled {
			seenPods[assignment.PodID]++
		}
		for _, failure := range decision.Unschedulable {
			seenPods[failure.PodID]++
		}

		// Check that all original pod IDs appear exactly once
		for _, podID := range podIDs {
			count, exists := seenPods[podID]
			if !exists {
				t.Fatalf("Pod ID %d not found in decision", podID)
			}
			if count != 1 {
				t.Fatalf("Pod ID %d appears %d times, expected 1", podID, count)
			}
		}

		// Check that no extra pod IDs appear
		if len(seenPods) != numPods {
			t.Fatalf("Expected %d unique pod IDs, got %d", numPods, len(seenPods))
		}
	})
}

// Test that Begin returns error when a round is already active
func TestBegin_ReturnsErrorWhenRoundActive(t *testing.T) {
	round := NewSchedulingRound(5 * time.Second)

	// Start first round
	err := round.Begin(5)
	if err != nil {
		t.Fatalf("First Begin failed: %v", err)
	}

	// Try to start second round while first is active
	err = round.Begin(3)
	if err == nil {
		t.Fatal("Expected error when starting second round, got nil")
	}
	if err.Error() != "scheduling round already in progress" {
		t.Fatalf("Expected 'scheduling round already in progress', got: %v", err)
	}
}

// Test that Begin with n=0 immediately returns empty decision
func TestBegin_ZeroPodsReturnsImmediately(t *testing.T) {
	round := NewSchedulingRound(5 * time.Second)

	err := round.Begin(0)
	if err != nil {
		t.Fatalf("Begin(0) failed: %v", err)
	}

	ctx := context.Background()
	decision, err := round.Wait(ctx)
	if err != nil {
		t.Fatalf("Wait failed: %v", err)
	}

	if len(decision.Scheduled) != 0 {
		t.Fatalf("Expected 0 scheduled pods, got %d", len(decision.Scheduled))
	}
	if len(decision.Unschedulable) != 0 {
		t.Fatalf("Expected 0 unschedulable pods, got %d", len(decision.Unschedulable))
	}
}

// Test that Wait times out when not all pods are resolved
func TestWait_TimesOutWhenIncomplete(t *testing.T) {
	round := NewSchedulingRound(100 * time.Millisecond)

	err := round.Begin(5)
	if err != nil {
		t.Fatalf("Begin failed: %v", err)
	}

	// Only record 3 out of 5 decisions
	round.RecordBinding("cspod-1", "csnode-1")
	round.RecordBinding("cspod-2", "csnode-2")
	round.RecordFailure("cspod-3", "Unschedulable")

	ctx := context.Background()
	_, err = round.Wait(ctx)
	if err == nil {
		t.Fatal("Expected timeout error, got nil")
	}
}

// Test that Wait returns partial results (bindings + failures) on timeout.
// This is critical for Volcano: when some pods are bound and others are silently
// skipped, the partial result must preserve the successful bindings.
func TestWait_PartialResultPreservedOnTimeout(t *testing.T) {
	round := NewSchedulingRound(100 * time.Millisecond)

	err := round.Begin(5)
	if err != nil {
		t.Fatalf("Begin failed: %v", err)
	}

	// Record 2 bindings and 1 failure out of 5 expected
	round.RecordBinding("cspod-1", "csnode-1")
	round.RecordBinding("cspod-2", "csnode-2")
	round.RecordFailure("cspod-3", "Unschedulable")

	ctx := context.Background()
	decision, err := round.Wait(ctx)
	if err == nil {
		t.Fatal("Expected timeout error")
	}

	// The partial result must contain the 2 bindings and 1 failure
	if len(decision.Scheduled) != 2 {
		t.Fatalf("Expected 2 scheduled pods in partial result, got %d", len(decision.Scheduled))
	}
	if len(decision.Unschedulable) != 1 {
		t.Fatalf("Expected 1 unschedulable pod in partial result, got %d", len(decision.Unschedulable))
	}
}

// Test that Reset cancels an active round
func TestReset_CancelsActiveRound(t *testing.T) {
	round := NewSchedulingRound(5 * time.Second)

	err := round.Begin(5)
	if err != nil {
		t.Fatalf("Begin failed: %v", err)
	}

	// Reset while round is active
	round.Reset()

	// Should be able to start a new round
	err = round.Begin(3)
	if err != nil {
		t.Fatalf("Begin after Reset failed: %v", err)
	}
}

// Test that BindingTimestamp is recorded correctly
func TestRecordBinding_RecordsTimestamp(t *testing.T) {
	round := NewSchedulingRound(5 * time.Second)

	err := round.Begin(1)
	if err != nil {
		t.Fatalf("Begin failed: %v", err)
	}

	beforeTime := time.Now()
	round.RecordBinding("cspod-42", "csnode-7")
	afterTime := time.Now()

	ctx := context.Background()
	decision, err := round.Wait(ctx)
	if err != nil {
		t.Fatalf("Wait failed: %v", err)
	}

	if len(decision.Scheduled) != 1 {
		t.Fatalf("Expected 1 scheduled pod, got %d", len(decision.Scheduled))
	}

	assignment := decision.Scheduled[0]
	if assignment.PodID != 42 {
		t.Fatalf("Expected pod ID 42, got %d", assignment.PodID)
	}
	if assignment.NodeID != 7 {
		t.Fatalf("Expected node ID 7, got %d", assignment.NodeID)
	}

	// Verify timestamp is within the expected range
	if assignment.BindingTimestamp.Before(beforeTime) || assignment.BindingTimestamp.After(afterTime) {
		t.Fatalf("BindingTimestamp %v is outside expected range [%v, %v]",
			assignment.BindingTimestamp, beforeTime, afterTime)
	}
}

// Test extractID helper function
func TestExtractID(t *testing.T) {
	tests := []struct {
		name     string
		prefix   string
		expected int
	}{
		{"cspod-42", "cspod-", 42},
		{"csnode-7", "csnode-", 7},
		{"cspod-0", "cspod-", 0},
		{"cspod-999", "cspod-", 999},
	}

	for _, tt := range tests {
		result := extractID(tt.name, tt.prefix)
		if result != tt.expected {
			t.Errorf("extractID(%q, %q) = %d, expected %d", tt.name, tt.prefix, result, tt.expected)
		}
	}
}
