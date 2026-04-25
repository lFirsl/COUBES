package scheduler

import (
	"sort"
	"time"
)

// TestModeScheduler performs deterministic round-robin pod-to-node assignment
// with resource-aware scheduling: pods are only placed on nodes that have
// enough free PEs, mirroring real kube-scheduler behaviour.
type TestModeScheduler struct{}

// SchedulerPod represents the minimal pod information needed for scheduling.
type SchedulerPod struct {
	ID   int
	Name string
	Pes  int // Number of PEs (cores) required
}

// SchedulerNode represents the minimal node information needed for scheduling.
type SchedulerNode struct {
	ID   int
	Name string
	Pes  int // Total number of PEs (cores) available
}

// Schedule assigns pods to nodes in round-robin order, respecting PE capacity.
// A pod is placed on the next node (in round-robin order) that has enough free PEs.
// If no node can fit a pod, it is returned as Unschedulable.
func (s *TestModeScheduler) Schedule(pods []SchedulerPod, nodes []SchedulerNode) BatchDecision {
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

	// Sort nodes lexicographically by name for deterministic ordering
	sortedNodes := make([]SchedulerNode, len(nodes))
	copy(sortedNodes, nodes)
	sort.Slice(sortedNodes, func(i, j int) bool {
		return sortedNodes[i].Name < sortedNodes[j].Name
	})

	// Track free PEs per node
	freePes := make([]int, len(sortedNodes))
	for i, n := range sortedNodes {
		freePes[i] = n.Pes
	}

	var scheduled []PodAssignment
	var unschedulable []PodFailure
	now := time.Now()
	rrIndex := 0 // round-robin starting index

	for _, pod := range pods {
		placed := false
		// Try each node starting from rrIndex, wrapping around
		for attempt := 0; attempt < len(sortedNodes); attempt++ {
			idx := (rrIndex + attempt) % len(sortedNodes)
			if freePes[idx] >= pod.Pes {
				freePes[idx] -= pod.Pes
				scheduled = append(scheduled, PodAssignment{
					PodID:            pod.ID,
					NodeID:           sortedNodes[idx].ID,
					BindingTimestamp: now,
				})
				rrIndex = (idx + 1) % len(sortedNodes)
				placed = true
				break
			}
		}
		if !placed {
			unschedulable = append(unschedulable, PodFailure{
				PodID:  pod.ID,
				Reason: "insufficient PEs on all nodes",
			})
		}
	}

	if scheduled == nil {
		scheduled = []PodAssignment{}
	}
	if unschedulable == nil {
		unschedulable = []PodFailure{}
	}

	return BatchDecision{
		Scheduled:     scheduled,
		Unschedulable: unschedulable,
	}
}
