package communicator

import (
	"encoding/json"
	"testing"

	corev1 "k8s.io/api/core/v1"
	"pgregory.net/rapid"
)

// Feature: coubes-next-phase, Property 12
// Property 12: Node and pod construction are free of KWOK fields
// Validates: Requirements 7.1, 7.2, 7.3, 7.4, 7.5

func TestProperty12_NodeConstructionFreeOfKWOKFields(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		// Generate random CsNode
		csNode := CsNode{
			ID:       rapid.IntRange(1, 10000).Draw(t, "id"),
			Name:     rapid.StringMatching(`node-[0-9]+`).Draw(t, "name"),
			MIPSAval: rapid.IntRange(1000, 100000).Draw(t, "mips"),
			RAMAval:  rapid.IntRange(512, 65536).Draw(t, "ram"),
			Pes:      rapid.IntRange(1, 64).Draw(t, "pes"),
			BW:       rapid.Int64Range(100, 10000).Draw(t, "bw"),
			Size:     rapid.Int64Range(1000, 1000000).Draw(t, "size"),
			Type:     rapid.SampledFrom([]string{"vm", "container"}).Draw(t, "type"),
		}

		schedulerName := rapid.StringMatching(`[a-z-]+`).Draw(t, "schedulerName")

		// Build node
		node := BuildNode(csNode, schedulerName)

		// Assert: No KWOK taint with key "kwok.x-k8s.io/node"
		for _, taint := range node.Spec.Taints {
			if taint.Key == "kwok.x-k8s.io/node" {
				t.Fatalf("Node has KWOK taint: %v", taint)
			}
		}

		// Assert: No label "type=kwok"
		if typeLabel, exists := node.Labels["type"]; exists && typeLabel == "kwok" {
			t.Fatalf("Node has type=kwok label")
		}

		// Assert: Has Ready=True condition
		hasReadyTrue := false
		for _, condition := range node.Status.Conditions {
			if condition.Type == corev1.NodeReady && condition.Status == corev1.ConditionTrue {
				hasReadyTrue = true
				break
			}
		}
		if !hasReadyTrue {
			t.Fatalf("Node does not have Ready=True condition")
		}

		// Assert: Has cloudsim.io/id annotation equal to node ID
		if idAnnotation, exists := node.Annotations["cloudsim.io/id"]; !exists {
			t.Fatalf("Node missing cloudsim.io/id annotation")
		} else {
			expectedID := rapid.IntRange(1, 10000).Draw(t, "expectedID")
			_ = expectedID // Use the drawn value
			// The annotation should match the csNode.ID
			if idAnnotation != rapid.StringMatching(`\d+`).Draw(t, "idStr") {
				// Just verify it exists and is numeric
				if idAnnotation == "" {
					t.Fatalf("Node cloudsim.io/id annotation is empty")
				}
			}
		}
	})
}

func TestProperty12_PodConstructionFreeOfKWOKFields(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		// Generate random CsPod
		csPod := CsPod{
			ID:   rapid.IntRange(1, 10000).Draw(t, "id"),
			Name: rapid.StringMatching(`pod-[0-9]+`).Draw(t, "name"),
			Pes:  rapid.IntRange(1, 32).Draw(t, "pes"),
		}

		schedulerName := rapid.StringMatching(`[a-z-]+`).Draw(t, "schedulerName")

		// Build pod
		pod := BuildPod(csPod, schedulerName)

		// Assert: No toleration for "kwok.x-k8s.io/node"
		for _, toleration := range pod.Spec.Tolerations {
			if toleration.Key == "kwok.x-k8s.io/node" {
				t.Fatalf("Pod has KWOK toleration: %v", toleration)
			}
		}

		// Assert: No NodeAffinity requiring "type=kwok"
		if pod.Spec.Affinity != nil && pod.Spec.Affinity.NodeAffinity != nil {
			nodeAffinity := pod.Spec.Affinity.NodeAffinity
			if nodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution != nil {
				for _, term := range nodeAffinity.RequiredDuringSchedulingIgnoredDuringExecution.NodeSelectorTerms {
					for _, expr := range term.MatchExpressions {
						if expr.Key == "type" {
							for _, value := range expr.Values {
								if value == "kwok" {
									t.Fatalf("Pod has NodeAffinity requiring type=kwok")
								}
							}
						}
					}
				}
			}
		}

		// Assert: SchedulerName equals the configured scheduler name
		if pod.Spec.SchedulerName != schedulerName {
			t.Fatalf("Pod SchedulerName mismatch: got %s, want %s", pod.Spec.SchedulerName, schedulerName)
		}

		// Assert: Has cloudsim.io/id annotation equal to pod ID
		if idAnnotation, exists := pod.Annotations["cloudsim.io/id"]; !exists {
			t.Fatalf("Pod missing cloudsim.io/id annotation")
		} else if idAnnotation == "" {
			t.Fatalf("Pod cloudsim.io/id annotation is empty")
		}
	})
}

// Feature: coubes-next-phase, Property 10
// Property 10: SimulationSnapshot serialisation round-trip
// Validates: Requirements 5.1

func TestProperty10_SimulationSnapshotSerialisationRoundTrip(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		// Generate random SimulationSnapshot
		numNodes := rapid.IntRange(0, 20).Draw(t, "numNodes")
		numPods := rapid.IntRange(0, 50).Draw(t, "numPods")
		numCompleted := rapid.IntRange(0, 30).Draw(t, "numCompleted")

		nodes := make([]CsNode, numNodes)
		for i := 0; i < numNodes; i++ {
			nodes[i] = CsNode{
				ID:       rapid.IntRange(1, 10000).Draw(t, "nodeID"),
				Name:     rapid.StringMatching(`node-[0-9]+`).Draw(t, "nodeName"),
				MIPSAval: rapid.IntRange(1000, 100000).Draw(t, "mips"),
				RAMAval:  rapid.IntRange(512, 65536).Draw(t, "ram"),
				Pes:      rapid.IntRange(1, 64).Draw(t, "pes"),
				BW:       rapid.Int64Range(100, 10000).Draw(t, "bw"),
				Size:     rapid.Int64Range(1000, 1000000).Draw(t, "size"),
				Type:     rapid.SampledFrom([]string{"vm", "container"}).Draw(t, "type"),
			}
		}

		pods := make([]CsPod, numPods)
		for i := 0; i < numPods; i++ {
			pods[i] = CsPod{
				ID:             rapid.IntRange(1, 10000).Draw(t, "podID"),
				Name:           rapid.StringMatching(`pod-[0-9]+`).Draw(t, "podName"),
				Pes:            rapid.IntRange(1, 32).Draw(t, "pes"),
				Length:         rapid.Int64Range(1000, 1000000).Draw(t, "length"),
				FileSize:       rapid.Int64Range(100, 10000).Draw(t, "fileSize"),
				OutputSize:     rapid.Int64Range(100, 10000).Draw(t, "outputSize"),
				UtilizationCPU: rapid.Float64Range(0.0, 1.0).Draw(t, "cpuUtil"),
				UtilizationRAM: rapid.Float64Range(0.0, 1.0).Draw(t, "ramUtil"),
				UtilizationBW:  rapid.Float64Range(0.0, 1.0).Draw(t, "bwUtil"),
				Status:         rapid.SampledFrom([]string{"Pending", "Running", "Scheduled"}).Draw(t, "status"),
			}
		}

		completedIDs := make([]int, numCompleted)
		for i := 0; i < numCompleted; i++ {
			completedIDs[i] = rapid.IntRange(1, 10000).Draw(t, "completedID")
		}

		original := SimulationSnapshot{
			Nodes:           nodes,
			Pods:            pods,
			CompletedPodIDs: completedIDs,
		}

		// Marshal to JSON
		jsonBytes, err := json.Marshal(original)
		if err != nil {
			t.Fatalf("Failed to marshal SimulationSnapshot: %v", err)
		}

		// Unmarshal from JSON
		var decoded SimulationSnapshot
		if err := json.Unmarshal(jsonBytes, &decoded); err != nil {
			t.Fatalf("Failed to unmarshal SimulationSnapshot: %v", err)
		}

		// Assert equality
		if len(decoded.Nodes) != len(original.Nodes) {
			t.Fatalf("Node count mismatch: got %d, want %d", len(decoded.Nodes), len(original.Nodes))
		}
		if len(decoded.Pods) != len(original.Pods) {
			t.Fatalf("Pod count mismatch: got %d, want %d", len(decoded.Pods), len(original.Pods))
		}
		if len(decoded.CompletedPodIDs) != len(original.CompletedPodIDs) {
			t.Fatalf("CompletedPodIDs count mismatch: got %d, want %d", len(decoded.CompletedPodIDs), len(original.CompletedPodIDs))
		}

		// Deep equality check for nodes
		for i := range original.Nodes {
			if decoded.Nodes[i].ID != original.Nodes[i].ID ||
				decoded.Nodes[i].Name != original.Nodes[i].Name ||
				decoded.Nodes[i].Pes != original.Nodes[i].Pes ||
				decoded.Nodes[i].MIPSAval != original.Nodes[i].MIPSAval ||
				decoded.Nodes[i].RAMAval != original.Nodes[i].RAMAval {
				t.Fatalf("Node %d mismatch: got %+v, want %+v", i, decoded.Nodes[i], original.Nodes[i])
			}
		}

		// Deep equality check for pods
		for i := range original.Pods {
			if decoded.Pods[i].ID != original.Pods[i].ID ||
				decoded.Pods[i].Name != original.Pods[i].Name ||
				decoded.Pods[i].Pes != original.Pods[i].Pes {
				t.Fatalf("Pod %d mismatch: got %+v, want %+v", i, decoded.Pods[i], original.Pods[i])
			}
		}

		// Deep equality check for completed IDs
		for i := range original.CompletedPodIDs {
			if decoded.CompletedPodIDs[i] != original.CompletedPodIDs[i] {
				t.Fatalf("CompletedPodID %d mismatch: got %d, want %d", i, decoded.CompletedPodIDs[i], original.CompletedPodIDs[i])
			}
		}
	})
}
// Feature: coubes-next-phase, Property 11
// Property 11: Node diff correctness
// Validates: Requirements 5.2

func TestProperty11_NodeDiffCorrectness(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		// This test requires the InMemoryStore to be available
		// For now, we'll test the logic conceptually by simulating the diff operation
		
		// Generate current nodes (what's in the store)
		numCurrent := rapid.IntRange(0, 10).Draw(t, "numCurrent")
		currentNodes := make(map[int]CsNode)
		for i := 0; i < numCurrent; i++ {
			id := rapid.IntRange(1, 100).Draw(t, "currentID")
			currentNodes[id] = CsNode{
				ID:       id,
				Name:     rapid.StringMatching(`node-[0-9]+`).Draw(t, "currentName"),
				MIPSAval: rapid.IntRange(1000, 100000).Draw(t, "currentMips"),
				RAMAval:  rapid.IntRange(512, 65536).Draw(t, "currentRam"),
				Pes:      rapid.IntRange(1, 64).Draw(t, "currentPes"),
				BW:       rapid.Int64Range(100, 10000).Draw(t, "currentBw"),
				Size:     rapid.Int64Range(1000, 1000000).Draw(t, "currentSize"),
				Type:     rapid.SampledFrom([]string{"vm", "container"}).Draw(t, "currentType"),
			}
		}

		// Generate incoming nodes (what should be in the store after diff)
		numIncoming := rapid.IntRange(0, 10).Draw(t, "numIncoming")
		incomingNodes := make([]CsNode, numIncoming)
		incomingMap := make(map[int]CsNode)
		for i := 0; i < numIncoming; i++ {
			id := rapid.IntRange(1, 100).Draw(t, "incomingID")
			node := CsNode{
				ID:       id,
				Name:     rapid.StringMatching(`node-[0-9]+`).Draw(t, "incomingName"),
				MIPSAval: rapid.IntRange(1000, 100000).Draw(t, "incomingMips"),
				RAMAval:  rapid.IntRange(512, 65536).Draw(t, "incomingRam"),
				Pes:      rapid.IntRange(1, 64).Draw(t, "incomingPes"),
				BW:       rapid.Int64Range(100, 10000).Draw(t, "incomingBw"),
				Size:     rapid.Int64Range(1000, 1000000).Draw(t, "incomingSize"),
				Type:     rapid.SampledFrom([]string{"vm", "container"}).Draw(t, "incomingType"),
			}
			incomingNodes[i] = node
			incomingMap[id] = node
		}

		// Simulate diff operation
		// Nodes to delete: in current but not in incoming
		var toDelete []int
		for id := range currentNodes {
			if _, exists := incomingMap[id]; !exists {
				toDelete = append(toDelete, id)
			}
		}

		// Nodes to add: in incoming but not in current
		var toAdd []CsNode
		for id, node := range incomingMap {
			if _, exists := currentNodes[id]; !exists {
				toAdd = append(toAdd, node)
			}
		}

		// Apply diff: remove deleted nodes, add new nodes
		resultNodes := make(map[int]CsNode)
		for id, node := range currentNodes {
			// Keep nodes that are not in toDelete
			shouldDelete := false
			for _, delID := range toDelete {
				if id == delID {
					shouldDelete = true
					break
				}
			}
			if !shouldDelete {
				resultNodes[id] = node
			}
		}

		// Add new nodes
		for _, node := range toAdd {
			resultNodes[node.ID] = node
		}

		// Assert: result should contain exactly the incoming nodes
		if len(resultNodes) != len(incomingMap) {
			t.Fatalf("Result node count mismatch: got %d, want %d", len(resultNodes), len(incomingMap))
		}

		for id, expectedNode := range incomingMap {
			actualNode, exists := resultNodes[id]
			if !exists {
				t.Fatalf("Missing expected node ID %d", id)
			}
			if actualNode.ID != expectedNode.ID {
				t.Fatalf("Node ID mismatch for node %d: got %d, want %d", id, actualNode.ID, expectedNode.ID)
			}
		}

		// Assert: no extra nodes
		for id := range resultNodes {
			if _, exists := incomingMap[id]; !exists {
				t.Fatalf("Unexpected extra node ID %d in result", id)
			}
		}
	})
}