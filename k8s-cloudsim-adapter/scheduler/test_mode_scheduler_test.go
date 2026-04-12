package scheduler

import (
	"fmt"
	"sort"
	"testing"

	"pgregory.net/rapid"
)

// Feature: coubes-next-phase, Property 1: Assignment completeness and uniqueness
func TestProperty1_AssignmentCompletenessAndUniqueness(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		numPods := rapid.IntRange(0, 50).Draw(t, "numPods")
		numNodes := rapid.IntRange(1, 20).Draw(t, "numNodes")

		pods := make([]SchedulerPod, numPods)
		for i := 0; i < numPods; i++ {
			pods[i] = SchedulerPod{ID: i + 1, Name: fmt.Sprintf("cspod-%d", i+1)}
		}

		nodes := make([]SchedulerNode, numNodes)
		for i := 0; i < numNodes; i++ {
			nodes[i] = SchedulerNode{ID: i + 1, Name: fmt.Sprintf("csnode-%d", i+1)}
		}

		scheduler := &TestModeScheduler{}
		decision := scheduler.Schedule(pods, nodes)

		totalEntries := len(decision.Scheduled) + len(decision.Unschedulable)
		if totalEntries != numPods {
			t.Fatalf("Expected %d total entries, got %d", numPods, totalEntries)
		}

		seenPods := make(map[int]int)
		for _, assignment := range decision.Scheduled {
			seenPods[assignment.PodID]++
		}
		for _, failure := range decision.Unschedulable {
			seenPods[failure.PodID]++
		}

		for _, pod := range pods {
			count, exists := seenPods[pod.ID]
			if !exists {
				t.Fatalf("Pod ID %d not found in decision", pod.ID)
			}
			if count != 1 {
				t.Fatalf("Pod ID %d appears %d times, expected 1", pod.ID, count)
			}
		}
	})
}

// Feature: coubes-next-phase, Property 2: Round-robin formula correctness
func TestProperty2_RoundRobinFormulaCorrectness(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		numPods := rapid.IntRange(0, 50).Draw(t, "numPods")
		numNodes := rapid.IntRange(1, 20).Draw(t, "numNodes")

		pods := make([]SchedulerPod, numPods)
		for i := 0; i < numPods; i++ {
			pods[i] = SchedulerPod{ID: i + 1, Name: fmt.Sprintf("cspod-%d", i+1)}
		}

		nodeNames := rapid.SliceOfNDistinct(
			rapid.StringMatching(`csnode-[0-9]+`),
			numNodes, numNodes,
			func(s string) string { return s },
		).Draw(t, "nodeNames")

		nodes := make([]SchedulerNode, numNodes)
		for i := 0; i < numNodes; i++ {
			nodes[i] = SchedulerNode{ID: i + 1, Name: nodeNames[i]}
		}

		sortedNodes := make([]SchedulerNode, len(nodes))
		copy(sortedNodes, nodes)
		sort.Slice(sortedNodes, func(i, j int) bool {
			return sortedNodes[i].Name < sortedNodes[j].Name
		})

		scheduler := &TestModeScheduler{}
		decision := scheduler.Schedule(pods, nodes)

		if len(decision.Scheduled) != numPods {
			t.Fatalf("Expected %d scheduled pods, got %d", numPods, len(decision.Scheduled))
		}

		for i, assignment := range decision.Scheduled {
			expectedNodeIndex := i % numNodes
			expectedNodeID := extractID(sortedNodes[expectedNodeIndex].Name, "csnode-")
			if assignment.NodeID != expectedNodeID {
				t.Fatalf("Assignment %d: expected node ID %d, got %d", i, expectedNodeID, assignment.NodeID)
			}
		}

		decision2 := scheduler.Schedule(pods, nodes)
		for i := 0; i < len(decision.Scheduled); i++ {
			if decision2.Scheduled[i].NodeID != decision.Scheduled[i].NodeID {
				t.Fatalf("Second run produced different assignment at %d", i)
			}
		}
	})
}

// Feature: coubes-next-phase, Property 3: Balance invariant
func TestProperty3_BalanceInvariant(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		numPods := rapid.IntRange(0, 50).Draw(t, "numPods")
		numNodes := rapid.IntRange(1, 20).Draw(t, "numNodes")

		pods := make([]SchedulerPod, numPods)
		for i := 0; i < numPods; i++ {
			pods[i] = SchedulerPod{ID: i + 1, Name: fmt.Sprintf("cspod-%d", i+1)}
		}

		nodes := make([]SchedulerNode, numNodes)
		for i := 0; i < numNodes; i++ {
			nodes[i] = SchedulerNode{ID: i + 1, Name: fmt.Sprintf("csnode-%d", i+1)}
		}

		scheduler := &TestModeScheduler{}
		decision := scheduler.Schedule(pods, nodes)

		nodeAssignments := make(map[int]int)
		for _, assignment := range decision.Scheduled {
			nodeAssignments[assignment.NodeID]++
		}

		floorCount := numPods / numNodes
		ceilCount := (numPods + numNodes - 1) / numNodes

		for nodeID, count := range nodeAssignments {
			if count != floorCount && count != ceilCount {
				t.Fatalf("Node %d has %d assignments, expected %d or %d", nodeID, count, floorCount, ceilCount)
			}
		}
	})
}

// Feature: coubes-next-phase, Property 4: BindingTimestamp presence
func TestProperty4_BindingTimestampPresence(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		numPods := rapid.IntRange(0, 50).Draw(t, "numPods")
		numNodes := rapid.IntRange(1, 20).Draw(t, "numNodes")

		pods := make([]SchedulerPod, numPods)
		for i := 0; i < numPods; i++ {
			pods[i] = SchedulerPod{ID: i + 1, Name: fmt.Sprintf("cspod-%d", i+1)}
		}

		nodes := make([]SchedulerNode, numNodes)
		for i := 0; i < numNodes; i++ {
			nodes[i] = SchedulerNode{ID: i + 1, Name: fmt.Sprintf("csnode-%d", i+1)}
		}

		scheduler := &TestModeScheduler{}
		decision := scheduler.Schedule(pods, nodes)

		for i, assignment := range decision.Scheduled {
			if assignment.BindingTimestamp.IsZero() {
				t.Fatalf("Assignment %d has zero BindingTimestamp", i)
			}
		}
	})
}

func TestSchedule_ZeroPods_EmptyDecision(t *testing.T) {
	scheduler := &TestModeScheduler{}
	pods := []SchedulerPod{}
	nodes := []SchedulerNode{{ID: 1, Name: "csnode-1"}}
	decision := scheduler.Schedule(pods, nodes)
	if len(decision.Scheduled) != 0 || len(decision.Unschedulable) != 0 {
		t.Error("Expected empty decision")
	}
}

func TestSchedule_ZeroNodes_AllUnschedulable(t *testing.T) {
	scheduler := &TestModeScheduler{}
	pods := []SchedulerPod{{ID: 1, Name: "cspod-1"}, {ID: 2, Name: "cspod-2"}, {ID: 3, Name: "cspod-3"}}
	nodes := []SchedulerNode{}
	decision := scheduler.Schedule(pods, nodes)
	if len(decision.Scheduled) != 0 || len(decision.Unschedulable) != 3 {
		t.Fatalf("Expected 3 unschedulable, got %d", len(decision.Unschedulable))
	}
	for i, failure := range decision.Unschedulable {
		if failure.Reason != "no nodes available" {
			t.Errorf("Failure %d: wrong reason '%s'", i, failure.Reason)
		}
	}
}

func TestSchedule_OnePodOneNode_SingleAssignment(t *testing.T) {
	scheduler := &TestModeScheduler{}
	pods := []SchedulerPod{{ID: 42, Name: "cspod-42"}}
	nodes := []SchedulerNode{{ID: 7, Name: "csnode-7"}}
	decision := scheduler.Schedule(pods, nodes)
	if len(decision.Scheduled) != 1 {
		t.Fatalf("Expected 1 scheduled pod, got %d", len(decision.Scheduled))
	}
	if decision.Scheduled[0].PodID != 42 || decision.Scheduled[0].NodeID != 7 {
		t.Error("Wrong assignment")
	}
}

func TestSchedule_FivePodsThreeNodes_RoundRobinPattern(t *testing.T) {
	scheduler := &TestModeScheduler{}
	pods := []SchedulerPod{
		{ID: 1, Name: "cspod-1"}, {ID: 2, Name: "cspod-2"}, {ID: 3, Name: "cspod-3"},
		{ID: 4, Name: "cspod-4"}, {ID: 5, Name: "cspod-5"},
	}
	nodes := []SchedulerNode{
		{ID: 10, Name: "csnode-10"}, {ID: 20, Name: "csnode-20"}, {ID: 30, Name: "csnode-30"},
	}
	decision := scheduler.Schedule(pods, nodes)
	expectedNodeIDs := []int{10, 20, 30, 10, 20}
	for i, assignment := range decision.Scheduled {
		if assignment.NodeID != expectedNodeIDs[i] {
			t.Errorf("Assignment %d: expected node %d, got %d", i, expectedNodeIDs[i], assignment.NodeID)
		}
	}
}

func TestSchedule_NodeSorting_LexicographicOrder(t *testing.T) {
	scheduler := &TestModeScheduler{}
	pods := []SchedulerPod{{ID: 1, Name: "cspod-1"}, {ID: 2, Name: "cspod-2"}, {ID: 3, Name: "cspod-3"}}
	nodes := []SchedulerNode{{ID: 30, Name: "csnode-30"}, {ID: 10, Name: "csnode-10"}, {ID: 20, Name: "csnode-20"}}
	decision := scheduler.Schedule(pods, nodes)
	expectedNodeIDs := []int{10, 20, 30}
	for i, assignment := range decision.Scheduled {
		if assignment.NodeID != expectedNodeIDs[i] {
			t.Errorf("Assignment %d: expected node %d, got %d", i, expectedNodeIDs[i], assignment.NodeID)
		}
	}
}
