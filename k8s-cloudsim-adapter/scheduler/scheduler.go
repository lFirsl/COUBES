package scheduler

import (
	"context"
	"fmt"
	"log"
	"strconv"
	"strings"
	"sync"
	"time"
)

// PodAssignment represents a successful pod-to-node binding.
type PodAssignment struct {
	PodID           int       `json:"podId"`
	NodeID          int       `json:"nodeId"`
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
	mu           sync.Mutex
	pending      int
	decisions    chan BatchDecision
	assignments  []PodAssignment
	failures     []PodFailure
	timeout      time.Duration
	active       bool
	startTime    time.Time // when the round began
	lateBindings map[int]PodAssignment // bindings that arrived after the round closed
}

func NewSchedulingRound(timeout time.Duration) *SchedulingRound {
	return &SchedulingRound{
		timeout:      timeout,
		assignments:  make([]PodAssignment, 0),
		failures:     make([]PodFailure, 0),
		decisions:    make(chan BatchDecision, 1),
		lateBindings: make(map[int]PodAssignment),
	}
}

func (r *SchedulingRound) Begin(n int) error {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.active {
		return fmt.Errorf("scheduling round already in progress")
	}

	r.active = true
	r.pending = n
	r.startTime = time.Now()
	r.assignments = make([]PodAssignment, 0, n)
	r.failures = make([]PodFailure, 0)

	log.Printf("Scheduling round started: expecting %d decisions", n)

	if n == 0 {
		r.decisions <- BatchDecision{Scheduled: r.assignments, Unschedulable: r.failures}
		r.active = false
	}

	return nil
}

func (r *SchedulingRound) RecordBinding(podName, nodeName string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	podID := extractID(podName, "cspod-")
	nodeID := extractID(nodeName, "csnode-")

	if !r.active {
		r.lateBindings[podID] = PodAssignment{
			PodID: podID, NodeID: nodeID, BindingTimestamp: time.Now(),
		}
		log.Printf("WARNING: Late binding (round inactive): pod=%s -> node=%s — scheduler bound a pod after the round closed", podName, nodeName)
		return
	}

	r.assignments = append(r.assignments, PodAssignment{
		PodID:           podID,
		NodeID:          nodeID,
		BindingTimestamp: time.Now(),
	})

	r.pending--
	log.Printf("Binding: pod=%s -> node=%s (%d/%d resolved)",
		podName, nodeName, len(r.assignments)+len(r.failures), len(r.assignments)+len(r.failures)+r.pending)

	if r.pending == 0 {
		log.Printf("Round complete: %d scheduled, %d failed in %v",
			len(r.assignments), len(r.failures), time.Since(r.startTime))
		r.decisions <- BatchDecision{Scheduled: r.assignments, Unschedulable: r.failures}
		r.active = false
	}
}

func (r *SchedulingRound) RecordFailure(podName, reason string) {
	r.mu.Lock()
	defer r.mu.Unlock()

	if !r.active {
		return
	}

	podID := extractID(podName, "cspod-")

	r.failures = append(r.failures, PodFailure{PodID: podID, Reason: reason})

	r.pending--
	log.Printf("Failure: pod=%s reason=%q (%d/%d resolved)",
		podName, reason, len(r.assignments)+len(r.failures), len(r.assignments)+len(r.failures)+r.pending)

	if r.pending == 0 {
		log.Printf("Round complete: %d scheduled, %d failed in %v",
			len(r.assignments), len(r.failures), time.Since(r.startTime))
		r.decisions <- BatchDecision{Scheduled: r.assignments, Unschedulable: r.failures}
		r.active = false
	}
}

func (r *SchedulingRound) Wait(ctx context.Context) (BatchDecision, error) {
	timeoutCtx, cancel := context.WithTimeout(ctx, r.timeout)
	defer cancel()

	stallCheck := time.NewTicker(10 * time.Second)
	defer stallCheck.Stop()
	lastResolved := 0

	for {
		select {
		case decision := <-r.decisions:
			return decision, nil
		case <-stallCheck.C:
			r.mu.Lock()
			resolved := len(r.assignments) + len(r.failures)
			pending := r.pending
			r.mu.Unlock()
			if resolved == lastResolved {
				log.Printf("STALL: no progress for 10s — %d resolved, %d still pending", resolved, pending)
			}
			lastResolved = resolved
		case <-timeoutCtx.Done():
			r.mu.Lock()
			scheduled := len(r.assignments)
			failed := len(r.failures)
			pending := r.pending
			r.active = false
			r.mu.Unlock()

			log.Printf("TIMEOUT: %d scheduled, %d failed, %d still pending after %v",
				scheduled, failed, pending, r.timeout)
			return BatchDecision{}, fmt.Errorf("scheduling timeout: %d/%d pods resolved (%d pending) within %v",
				scheduled+failed, scheduled+failed+pending, pending, r.timeout)
		}
	}
}

func (r *SchedulingRound) Reset() {
	r.mu.Lock()
	defer r.mu.Unlock()

	if r.active {
		select {
		case r.decisions <- BatchDecision{
			Scheduled: []PodAssignment{}, Unschedulable: []PodFailure{},
		}:
		default:
		}
		r.active = false
	}

	r.pending = 0
	r.assignments = make([]PodAssignment, 0)
	r.failures = make([]PodFailure, 0)
	r.lateBindings = make(map[int]PodAssignment)
	log.Printf("Scheduling round reset")
}

// DrainLateBindings returns and clears any bindings that arrived after the
// previous round closed. The communicator uses this to exclude already-bound
// pods from the next round and include them in the batch decision.
func (r *SchedulingRound) DrainLateBindings() []PodAssignment {
	r.mu.Lock()
	defer r.mu.Unlock()
	if len(r.lateBindings) == 0 {
		return nil
	}
	result := make([]PodAssignment, 0, len(r.lateBindings))
	for _, a := range r.lateBindings {
		result = append(result, a)
	}
	r.lateBindings = make(map[int]PodAssignment)
	return result
}

func extractID(name, prefix string) int {
	idStr := strings.TrimPrefix(name, prefix)
	id, err := strconv.Atoi(idStr)
	if err != nil {
		return -1
	}
	return id
}
