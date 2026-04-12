package scheduler

import (
	"sort"
	"time"
)

// TestModeScheduler performs deterministic round-robin pod-to-node assignment
// without contacting any external process.
type TestModeScheduler struct{}

// SchedulerPod represents the minimal pod information needed for scheduling.
type SchedulerPod struct {
	ID   int
	Name string
}

// SchedulerNode represents the minimal node information needed for scheduling.
type SchedulerNode struct {
	ID   int
	Name string
}

// Schedule assigns each pod in `pods` (zero-indexed in order received) to
// sortedNodes[i mod M], where sortedNodes is sorted lexicographically by node name.
// Returns a BatchDecision with BindingTimestamp set to time.Now() for each assignment.
// If len(nodes) == 0, all pods are returned as Unschedulable with reason "no nodes available".
func (s *TestModeScheduler) Schedule(pods []SchedulerPod, nodes []SchedulerNode) BatchDecision {
	// Handle empty nodes case
	if len(nodes) == 0 {
		failures := make([]PodFailure, len(pods))
		for i, pod := range pods {
			failures[i] = PodFailure{
				PodID:  pod.ID,
				Reason: "no nodes available",
			}
		}
		return BatchDecision{
			Scheduled:     []PodAssignment{},
			Unschedulable: failures,
		}
	}

	// Sort nodes lexicographically by name
	sortedNodes := make([]SchedulerNode, len(nodes))
	copy(sortedNodes, nodes)
	sort.Slice(sortedNodes, func(i, j int) bool {
		return sortedNodes[i].Name < sortedNodes[j].Name
	})

	// Assign pods in round-robin fashion
	assignments := make([]PodAssignment, len(pods))
	now := time.Now()
	
	for i, pod := range pods {
		nodeIndex := i % len(sortedNodes)
		assignments[i] = PodAssignment{
			PodID:            pod.ID,
			NodeID:           extractID(sortedNodes[nodeIndex].Name, "csnode-"),
			BindingTimestamp: now,
		}
	}

	return BatchDecision{
		Scheduled:     assignments,
		Unschedulable: []PodFailure{},
	}
}
