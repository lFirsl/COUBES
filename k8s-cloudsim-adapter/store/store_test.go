package store

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"pgregory.net/rapid"
)

// Feature: coubes-next-phase, Property 1: Node store round-trip
// For any set of v1.Node objects created in the InMemoryStore, GetNodes() must return a list containing all of them;
// after deleting a node by name, GetNodes() must not contain it.
func TestProperty1_NodeStoreRoundTrip(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		store := NewInMemoryStore()
		defer store.cancel()

		// Generate random nodes with unique names
		nodeCount := rapid.IntRange(1, 20).Draw(t, "nodeCount")
		nodeNames := make([]string, nodeCount)
		usedNames := make(map[string]bool)
		for i := 0; i < nodeCount; i++ {
			var nodeName string
			for {
				nodeName = fmt.Sprintf("node-%d", rapid.IntRange(0, 100000).Draw(t, fmt.Sprintf("nodeName-%d", i)))
				if !usedNames[nodeName] {
					usedNames[nodeName] = true
					break
				}
			}
			nodeNames[i] = nodeName
			node := &corev1.Node{
				ObjectMeta: metav1.ObjectMeta{
					Name: nodeName,
				},
				Status: corev1.NodeStatus{
					Capacity: corev1.ResourceList{
						corev1.ResourceCPU:    resource.MustParse(fmt.Sprintf("%d", rapid.IntRange(1, 64).Draw(t, fmt.Sprintf("cpu-%d", i)))),
						corev1.ResourceMemory: resource.MustParse(fmt.Sprintf("%dGi", rapid.IntRange(1, 256).Draw(t, fmt.Sprintf("mem-%d", i)))),
					},
				},
			}
			store.CreateNode(node)
		}

		// Verify all nodes are present
		retrievedNodes := store.GetNodes()
		if len(retrievedNodes) != nodeCount {
			t.Fatalf("expected %d nodes, got %d", nodeCount, len(retrievedNodes))
		}

		nodeMap := make(map[string]bool)
		for _, n := range retrievedNodes {
			nodeMap[n.Name] = true
		}
		for _, name := range nodeNames {
			if !nodeMap[name] {
				t.Fatalf("node %s not found in GetNodes()", name)
			}
		}

		// Delete a random node
		if nodeCount > 0 {
			deleteIdx := rapid.IntRange(0, nodeCount-1).Draw(t, "deleteIdx")
			deletedName := nodeNames[deleteIdx]
			store.DeleteNode(deletedName)

			// Verify node is absent
			retrievedNodes = store.GetNodes()
			for _, n := range retrievedNodes {
				if n.Name == deletedName {
					t.Fatalf("deleted node %s still present in GetNodes()", deletedName)
				}
			}
		}
	})
}

// Feature: coubes-next-phase, Property 2: Pod store round-trip
// For any set of v1.Pod objects created in the InMemoryStore, GetPods() must return a list containing all of them;
// after updating a pod, GetPods() must reflect the updated values; after deleting a pod, GetPods() must not contain it.
func TestProperty2_PodStoreRoundTrip(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		store := NewInMemoryStore()
		defer store.cancel()

		// Generate random pods with unique names
		podCount := rapid.IntRange(1, 20).Draw(t, "podCount")
		podNames := make([]string, podCount)
		usedNames := make(map[string]bool)
		for i := 0; i < podCount; i++ {
			var podName string
			for {
				podName = fmt.Sprintf("pod-%d", rapid.IntRange(0, 100000).Draw(t, fmt.Sprintf("podName-%d", i)))
				if !usedNames[podName] {
					usedNames[podName] = true
					break
				}
			}
			podNames[i] = podName
			pod := &corev1.Pod{
				ObjectMeta: metav1.ObjectMeta{
					Name:      podName,
					Namespace: "default",
				},
				Spec: corev1.PodSpec{
					Containers: []corev1.Container{
						{
							Name:  "container",
							Image: "image",
						},
					},
				},
			}
			store.CreatePod(pod)
		}

		// Verify all pods are present
		retrievedPods := store.GetPods()
		if len(retrievedPods) != podCount {
			t.Fatalf("expected %d pods, got %d", podCount, len(retrievedPods))
		}

		podMap := make(map[string]bool)
		for _, p := range retrievedPods {
			podMap[p.Name] = true
		}
		for _, name := range podNames {
			if !podMap[name] {
				t.Fatalf("pod %s not found in GetPods()", name)
			}
		}

		// Update a random pod
		if podCount > 0 {
			updateIdx := rapid.IntRange(0, podCount-1).Draw(t, "updateIdx")
			updateName := podNames[updateIdx]
			updatedPod := &corev1.Pod{
				ObjectMeta: metav1.ObjectMeta{
					Name:      updateName,
					Namespace: "default",
					Labels: map[string]string{
						"updated": "true",
					},
				},
				Spec: corev1.PodSpec{
					NodeName: "some-node",
					Containers: []corev1.Container{
						{
							Name:  "container",
							Image: "image",
						},
					},
				},
			}
			store.UpdatePod(updateName, updatedPod)

			// Verify update is reflected
			retrievedPod, ok := store.GetPod(updateName)
			if !ok {
				t.Fatalf("updated pod %s not found", updateName)
			}
			if retrievedPod.Spec.NodeName != "some-node" {
				t.Fatalf("pod update not reflected: expected NodeName=some-node, got %s", retrievedPod.Spec.NodeName)
			}
			if retrievedPod.Labels["updated"] != "true" {
				t.Fatalf("pod labels not updated")
			}
		}

		// Delete a random pod
		if podCount > 0 {
			deleteIdx := rapid.IntRange(0, podCount-1).Draw(t, "deleteIdx")
			deletedName := podNames[deleteIdx]
			store.DeletePod(deletedName)

			// Verify pod is absent
			retrievedPods = store.GetPods()
			for _, p := range retrievedPods {
				if p.Name == deletedName {
					t.Fatalf("deleted pod %s still present in GetPods()", deletedName)
				}
			}
		}
	})
}

// Feature: coubes-next-phase, Property 3: Resource version monotonicity
// For any sequence of write operations (create, update, or delete) on the InMemoryStore,
// the resource version after each operation must be strictly greater than the resource version before that operation.
func TestProperty3_ResourceVersionMonotonicity(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		store := NewInMemoryStore()
		defer store.cancel()

		opCount := rapid.IntRange(1, 50).Draw(t, "opCount")
		lastRV := store.GetResourceVersion()

		for i := 0; i < opCount; i++ {
			opType := rapid.IntRange(0, 5).Draw(t, fmt.Sprintf("opType-%d", i))
			switch opType {
			case 0: // CreateNode
				nodeName := fmt.Sprintf("node-%d", i)
				node := &corev1.Node{
					ObjectMeta: metav1.ObjectMeta{Name: nodeName},
				}
				store.CreateNode(node)
			case 1: // UpdateNode
				if len(store.nodes) > 0 {
					// Pick a random existing node
					var nodeName string
					for name := range store.nodes {
						nodeName = name
						break
					}
					node := &corev1.Node{
						ObjectMeta: metav1.ObjectMeta{Name: nodeName},
					}
					store.UpdateNode(nodeName, node)
				} else {
					continue
				}
			case 2: // DeleteNode
				if len(store.nodes) > 0 {
					var nodeName string
					for name := range store.nodes {
						nodeName = name
						break
					}
					store.DeleteNode(nodeName)
				} else {
					continue
				}
			case 3: // CreatePod
				podName := fmt.Sprintf("pod-%d", i)
				pod := &corev1.Pod{
					ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
				}
				store.CreatePod(pod)
			case 4: // UpdatePod
				if len(store.pods) > 0 {
					var podName string
					for name := range store.pods {
						podName = name
						break
					}
					pod := &corev1.Pod{
						ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
					}
					store.UpdatePod(podName, pod)
				} else {
					continue
				}
			case 5: // DeletePod
				if len(store.pods) > 0 {
					var podName string
					for name := range store.pods {
						podName = name
						break
					}
					store.DeletePod(podName)
				} else {
					continue
				}
			}

			currentRV := store.GetResourceVersion()
			if currentRV <= lastRV {
				t.Fatalf("resource version not monotonic: last=%d, current=%d after operation %d", lastRV, currentRV, i)
			}
			lastRV = currentRV
		}
	})
}

// Feature: coubes-next-phase, Property 4: Write operations publish correct watch events
// For any node or pod, performing a create operation must publish an ADDED WatchEvent,
// an update must publish a MODIFIED WatchEvent, and a delete must publish a DELETED WatchEvent.
func TestProperty4_WatchEvents(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		store := NewInMemoryStore()
		defer store.cancel()

		// Subscribe to node and pod events
		nodeCh := store.SubscribeNodes()
		podCh := store.SubscribePods()

		// Collect events in background
		var nodeEvents []metav1.WatchEvent
		var podEvents []metav1.WatchEvent
		var wg sync.WaitGroup
		wg.Add(2)

		go func() {
			defer wg.Done()
			timeout := time.After(2 * time.Second)
			for {
				select {
				case event, ok := <-nodeCh:
					if !ok {
						return
					}
					nodeEvents = append(nodeEvents, event)
				case <-timeout:
					return
				}
			}
		}()

		go func() {
			defer wg.Done()
			timeout := time.After(2 * time.Second)
			for {
				select {
				case event, ok := <-podCh:
					if !ok {
						return
					}
					podEvents = append(podEvents, event)
				case <-timeout:
					return
				}
			}
		}()

		// Perform operations
		opCount := rapid.IntRange(1, 10).Draw(t, "opCount")
		expectedNodeEvents := []string{}
		expectedPodEvents := []string{}

		for i := 0; i < opCount; i++ {
			opType := rapid.IntRange(0, 5).Draw(t, fmt.Sprintf("opType-%d", i))
			switch opType {
			case 0: // CreateNode
				nodeName := fmt.Sprintf("node-%d", i)
				node := &corev1.Node{
					ObjectMeta: metav1.ObjectMeta{Name: nodeName},
				}
				store.CreateNode(node)
				expectedNodeEvents = append(expectedNodeEvents, "ADDED")
			case 1: // UpdateNode
				if len(store.nodes) > 0 {
					var nodeName string
					for name := range store.nodes {
						nodeName = name
						break
					}
					node := &corev1.Node{
						ObjectMeta: metav1.ObjectMeta{Name: nodeName},
					}
					store.UpdateNode(nodeName, node)
					expectedNodeEvents = append(expectedNodeEvents, "MODIFIED")
				}
			case 2: // DeleteNode
				if len(store.nodes) > 0 {
					var nodeName string
					for name := range store.nodes {
						nodeName = name
						break
					}
					store.DeleteNode(nodeName)
					expectedNodeEvents = append(expectedNodeEvents, "DELETED")
				}
			case 3: // CreatePod
				podName := fmt.Sprintf("pod-%d", i)
				pod := &corev1.Pod{
					ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
				}
				store.CreatePod(pod)
				expectedPodEvents = append(expectedPodEvents, "ADDED")
			case 4: // UpdatePod
				if len(store.pods) > 0 {
					var podName string
					for name := range store.pods {
						podName = name
						break
					}
					pod := &corev1.Pod{
						ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
					}
					store.UpdatePod(podName, pod)
					expectedPodEvents = append(expectedPodEvents, "MODIFIED")
				}
			case 5: // DeletePod
				if len(store.pods) > 0 {
					var podName string
					for name := range store.pods {
						podName = name
						break
					}
					store.DeletePod(podName)
					expectedPodEvents = append(expectedPodEvents, "DELETED")
				}
			}
		}

		// Wait a bit for events to propagate
		time.Sleep(100 * time.Millisecond)
		store.cancel()
		wg.Wait()

		// Verify event types match
		if len(nodeEvents) != len(expectedNodeEvents) {
			t.Fatalf("expected %d node events, got %d", len(expectedNodeEvents), len(nodeEvents))
		}
		for i, event := range nodeEvents {
			if event.Type != expectedNodeEvents[i] {
				t.Fatalf("node event %d: expected type %s, got %s", i, expectedNodeEvents[i], event.Type)
			}
		}

		if len(podEvents) != len(expectedPodEvents) {
			t.Fatalf("expected %d pod events, got %d", len(expectedPodEvents), len(podEvents))
		}
		for i, event := range podEvents {
			if event.Type != expectedPodEvents[i] {
				t.Fatalf("pod event %d: expected type %s, got %s", i, expectedPodEvents[i], event.Type)
			}
		}
	})
}

// Feature: coubes-next-phase, Property 5: Reset produces empty store
// For any InMemoryStore state (any number of nodes and pods), after calling Reset(),
// GetNodes() and GetPods() must both return empty lists, and the resource version must be 0.
func TestProperty5_ResetProducesEmptyStore(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		store := NewInMemoryStore()
		defer store.cancel()

		// Generate random pre-reset state
		nodeCount := rapid.IntRange(0, 20).Draw(t, "nodeCount")
		for i := 0; i < nodeCount; i++ {
			nodeName := fmt.Sprintf("node-%d", i)
			node := &corev1.Node{
				ObjectMeta: metav1.ObjectMeta{Name: nodeName},
			}
			store.CreateNode(node)
		}

		podCount := rapid.IntRange(0, 20).Draw(t, "podCount")
		for i := 0; i < podCount; i++ {
			podName := fmt.Sprintf("pod-%d", i)
			pod := &corev1.Pod{
				ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
			}
			store.CreatePod(pod)
		}

		// Reset
		store.Reset()

		// Verify empty state
		nodes := store.GetNodes()
		if len(nodes) != 0 {
			t.Fatalf("after reset, expected 0 nodes, got %d", len(nodes))
		}

		pods := store.GetPods()
		if len(pods) != 0 {
			t.Fatalf("after reset, expected 0 pods, got %d", len(pods))
		}

		rv := store.GetResourceVersion()
		if rv != 0 {
			t.Fatalf("after reset, expected resource version 0, got %d", rv)
		}
	})
}

// Feature: coubes-next-phase, Property 6: Reset empties store
// **Validates: Requirements 3.4**
// For any adapter state (arbitrary nodes and pods registered), calling Reset() must result
// in an empty InMemoryStore (zero nodes, zero pods) regardless of prior state.
func TestProperty6_ResetEmptiesStore(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		store := NewInMemoryStore()
		defer store.cancel()

		// Generate arbitrary nodes
		nodeCount := rapid.IntRange(0, 30).Draw(t, "nodeCount")
		for i := 0; i < nodeCount; i++ {
			nodeName := fmt.Sprintf("csnode-%d", rapid.IntRange(0, 100000).Draw(t, fmt.Sprintf("nodeID-%d", i)))
			node := &corev1.Node{
				ObjectMeta: metav1.ObjectMeta{
					Name: nodeName,
				},
				Status: corev1.NodeStatus{
					Capacity: corev1.ResourceList{
						corev1.ResourceCPU:    resource.MustParse(fmt.Sprintf("%dm", rapid.IntRange(100, 64000).Draw(t, fmt.Sprintf("cpu-%d", i)))),
						corev1.ResourceMemory: resource.MustParse(fmt.Sprintf("%dMi", rapid.IntRange(128, 262144).Draw(t, fmt.Sprintf("mem-%d", i)))),
					},
				},
			}
			store.CreateNode(node)
		}

		// Generate arbitrary pods
		podCount := rapid.IntRange(0, 50).Draw(t, "podCount")
		for i := 0; i < podCount; i++ {
			podName := fmt.Sprintf("cspod-%d", rapid.IntRange(0, 100000).Draw(t, fmt.Sprintf("podID-%d", i)))
			pod := &corev1.Pod{
				ObjectMeta: metav1.ObjectMeta{
					Name:      podName,
					Namespace: "default",
				},
				Spec: corev1.PodSpec{
					Containers: []corev1.Container{
						{
							Name:  "container",
							Image: "busybox",
							Resources: corev1.ResourceRequirements{
								Requests: corev1.ResourceList{
									corev1.ResourceCPU:    resource.MustParse(fmt.Sprintf("%dm", rapid.IntRange(10, 4000).Draw(t, fmt.Sprintf("podCPU-%d", i)))),
									corev1.ResourceMemory: resource.MustParse(fmt.Sprintf("%dMi", rapid.IntRange(16, 8192).Draw(t, fmt.Sprintf("podMem-%d", i)))),
								},
							},
						},
					},
				},
			}
			store.CreatePod(pod)
		}

		// Call Reset
		store.Reset()

		// Assert GetNodes() returns empty slice
		nodes := store.GetNodes()
		if len(nodes) != 0 {
			t.Fatalf("after Reset(), GetNodes() returned %d nodes, expected 0", len(nodes))
		}

		// Assert GetPods() returns empty slice
		pods := store.GetPods()
		if len(pods) != 0 {
			t.Fatalf("after Reset(), GetPods() returned %d pods, expected 0", len(pods))
		}
	})
}

// Test BroadcastServer basic functionality
func TestBroadcastServer_BasicFunctionality(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	source := make(chan int, 10)
	bs := NewBroadcastServer[int](ctx, source)

	// Subscribe two listeners
	ch1 := bs.Subscribe()
	ch2 := bs.Subscribe()

	// Send events
	source <- 1
	source <- 2
	source <- 3

	// Verify both listeners receive all events
	for i := 1; i <= 3; i++ {
		select {
		case val := <-ch1:
			if val != i {
				t.Fatalf("ch1: expected %d, got %d", i, val)
			}
		case <-time.After(1 * time.Second):
			t.Fatal("ch1: timeout waiting for event")
		}

		select {
		case val := <-ch2:
			if val != i {
				t.Fatalf("ch2: expected %d, got %d", i, val)
			}
		case <-time.After(1 * time.Second):
			t.Fatal("ch2: timeout waiting for event")
		}
	}

	// Cancel one subscription
	bs.CancelSubscription(ch1)

	// Send another event
	source <- 4

	// ch2 should receive it, ch1 should be closed
	select {
	case val := <-ch2:
		if val != 4 {
			t.Fatalf("ch2: expected 4, got %d", val)
		}
	case <-time.After(1 * time.Second):
		t.Fatal("ch2: timeout waiting for event")
	}

	select {
	case _, ok := <-ch1:
		if ok {
			t.Fatal("ch1 should be closed after cancellation")
		}
	case <-time.After(1 * time.Second):
		t.Fatal("ch1: timeout waiting for close")
	}
}
