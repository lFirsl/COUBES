package scheduler

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"
)

// PodAssignment represents a successful pod-to-node binding.
type PodAssignment struct {
	PodID            int       `json:"podId"`
	NodeID           int       `json:"nodeId"`
	BindingTimestamp time.Time `json:"bindingTimestamp"`
}

// PodFailure represents a pod that could not be scheduled.
type PodFailure struct {
	PodID  int    `json:"podId"`
	Reason string `json:"reason"`
}

// BatchDecision contains the complete set of scheduling decisions for a round.
type BatchDecision struct {
	Scheduled     []PodAssignment `json:"scheduled"`
	Unschedulable []PodFailure    `json:"unschedulable"`
}

// SchedulingRound manages synchronisation between the simulation-facing
// /schedule-pods handler and the kube-scheduler's binding callbacks.
type SchedulingRound struct {
	mu          sync.Mutex
	pending     int                // countdown: decremented on each binding or failure
	decisions   chan BatchDecision // unblocked when pending reaches 0
	assignments []PodAssignment    // accumulated scheduled pods
	failures    []PodFailure       // accumulated unschedulable pods
	timeout     time.Duration
	active      bool // true when a round is in progress
}

// NewSchedulingRound creates a new SchedulingRound with the specified timeout.
func NewSchedulingRound(timeout time.Duration) *SchedulingRound {
	return &SchedulingRound{
		timeout:     timeout,
		assignments: make([]PodAssignment, 0),
		failures:    make([]PodFailure, 0),
		decisions:   make(chan BatchDecision, 1),
	}
}

// Begin initialises a new scheduling round expecting n decisions.
// Returns an error if a round is already active (serialisation guard).
func (r *SchedulingRound) Begin(n int) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.active {
		return fmt.Errorf("scheduling round already in progress")
	}

	r.active = true
	r.pending = n
	r.assignments = make([]PodAssignment, 0, n)
	r.failures = make([]PodFailure, 0)

	// If n is 0, immediately send empty decision
	if n == 0 {
		r.decisions <- BatchDecision{
			Scheduled:     r.assignments,
			Unschedulable: r.failures,
		}
		r.active = false
	}

	return nil
}

// RecordBinding records a successful pod-to-node assignment.
// Decrements the pending counter and sends BatchDecision when it reaches zero.
func (r *SchedulingRound) RecordBinding(podName, nodeName string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if !r.active {
		return
	}

	// Extract CloudSim IDs from names
	podID := extractID(podName, "cspod-")
	nodeID := extractID(nodeName, "csnode-")

	r.assignments = append(r.assignments, PodAssignment{
		PodID:            podID,
		NodeID:           nodeID,
		BindingTimestamp: time.Now(),
	})

	r.pending--
	if r.pending == 0 {
		r.decisions <- BatchDecision{
			Scheduled:     r.assignments,
			Unschedulable: r.failures,
		}
		r.active = false
	}
}

// RecordFailure records a pod that could not be scheduled.
// Decrements the pending counter and sends BatchDecision when it reaches zero.
func (r *SchedulingRound) RecordFailure(podName, reason string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if !r.active {
		return
	}

	podID := extractID(podName, "cspod-")

	r.failures = append(r.failures, PodFailure{
		PodID:  podID,
		Reason: reason,
	})

	r.pending--
	if r.pending == 0 {
		r.decisions <- BatchDecision{
			Scheduled:     r.assignments,
			Unschedulable: r.failures,
		}
		r.active = false
	}
}

// Wait blocks until the BatchDecision is ready or the timeout expires.
// Returns HTTP 408-style error on timeout.
func (r *SchedulingRound) Wait(ctx context.Context) (BatchDecision, error) {
	timeoutCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()

	select {
	case decision := <-r.decisions:
		return decision, nil
	case <-timeoutCtx.Done():
		r.mu.Lock()
		defer r.mu.Unlock()
		r.active = false
		return BatchDecision{}, fmt.Errorf("scheduling timeout: not all pods resolved within %v", r.timeout)
	}
}

// Reset cancels the active round (if any) and resets state.
func (r *SchedulingRound) Reset() {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.active {
		// Send empty decision to unblock any waiting handler
		select {
		case r.decisions <- BatchDecision{
			Scheduled:     []PodAssignment{},
			Unschedulable: []PodFailure{},
		}:
		default:
		}
		r.active = false
	}

	r.pending = 0
	r.assignments = make([]PodAssignment, 0)
	r.failures = make([]PodFailure, 0)
}

// extractID extracts the numeric ID from a name with the given prefix.
// For example, extractID("cspod-42", "cspod-") returns 42.
func extractID(name, prefix string) int {
	idStr := strings.TrimPrefix(name, prefix)
	id, err := strconv.Atoi(idStr)
	if err != nil {
		return -1
	}
	return id
}
