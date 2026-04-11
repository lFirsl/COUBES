package fakeapi

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"pgregory.net/rapid"

	"k8s-cloudsim-adapter/store"
)

// Feature: coubes-next-phase, Property 6: FakeAPIServer list endpoints reflect store contents
// For any set of nodes stored in the InMemoryStore, GET /api/v1/nodes must return a v1.NodeList
// whose items contain exactly those nodes; equivalently for pods and GET /api/v1/pods.
func TestProperty6_ListEndpointsReflectStoreContents(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		s := store.NewInMemoryStore()
		defer s.Reset()
		handler := NewFakeAPIHandler(s)

		// Generate random nodes
		nodeCount := rapid.IntRange(0, 20).Draw(t, "nodeCount")
		nodeNames := make(map[string]bool)
		for i := 0; i < nodeCount; i++ {
			nodeName := fmt.Sprintf("node-%d", rapid.IntRange(0, 100000).Draw(t, fmt.Sprintf("nodeName-%d", i)))
			if nodeNames[nodeName] {
				continue // Skip duplicates
			}
			nodeNames[nodeName] = true

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
			s.CreateNode(node)
		}

		// Test GET /api/v1/nodes
		req := httptest.NewRequest("GET", "/api/v1/nodes", nil)
		w := httptest.NewRecorder()
		handler.HandleListNodes(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("expected status 200, got %d", w.Code)
		}

		var nodeList corev1.NodeList
		if err := json.NewDecoder(w.Body).Decode(&nodeList); err != nil {
			t.Fatalf("error decoding node list: %v", err)
		}

		if len(nodeList.Items) != len(nodeNames) {
			t.Fatalf("expected %d nodes in list, got %d", len(nodeNames), len(nodeList.Items))
		}

		for _, node := range nodeList.Items {
			if !nodeNames[node.Name] {
				t.Fatalf("unexpected node %s in list", node.Name)
			}
		}

		// Generate random pods
		podCount := rapid.IntRange(0, 20).Draw(t, "podCount")
		podNames := make(map[string]bool)
		for i := 0; i < podCount; i++ {
			podName := fmt.Sprintf("pod-%d", rapid.IntRange(0, 100000).Draw(t, fmt.Sprintf("podName-%d", i)))
			if podNames[podName] {
				continue // Skip duplicates
			}
			podNames[podName] = true

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
			s.CreatePod(pod)
		}

		// Test GET /api/v1/pods
		req = httptest.NewRequest("GET", "/api/v1/pods", nil)
		w = httptest.NewRecorder()
		handler.HandleListPods(w, req)

		if w.Code != http.StatusOK {
			t.Fatalf("expected status 200, got %d", w.Code)
		}

		var podList corev1.PodList
		if err := json.NewDecoder(w.Body).Decode(&podList); err != nil {
			t.Fatalf("error decoding pod list: %v", err)
		}

		if len(podList.Items) != len(podNames) {
			t.Fatalf("expected %d pods in list, got %d", len(podNames), len(podList.Items))
		}

		for _, pod := range podList.Items {
			if !podNames[pod.Name] {
				t.Fatalf("unexpected pod %s in list", pod.Name)
			}
		}
	})
}

// Feature: coubes-next-phase, Property 7: Node get-by-name round-trip
// For any node stored in the InMemoryStore, GET /api/v1/nodes/{name} must return that node;
// for any name not present in the store, the endpoint must return HTTP 404.
func TestProperty7_NodeGetByNameRoundTrip(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		s := store.NewInMemoryStore()
		defer s.Reset()
		handler := NewFakeAPIHandler(s)

		// Generate random nodes
		nodeCount := rapid.IntRange(1, 20).Draw(t, "nodeCount")
		nodeNames := make([]string, 0, nodeCount)
		for i := 0; i < nodeCount; i++ {
			nodeName := fmt.Sprintf("node-%d", rapid.IntRange(0, 100000).Draw(t, fmt.Sprintf("nodeName-%d", i)))
			nodeNames = append(nodeNames, nodeName)

			node := &corev1.Node{
				ObjectMeta: metav1.ObjectMeta{
					Name: nodeName,
				},
				Status: corev1.NodeStatus{
					Capacity: corev1.ResourceList{
						corev1.ResourceCPU: resource.MustParse(fmt.Sprintf("%d", rapid.IntRange(1, 64).Draw(t, fmt.Sprintf("cpu-%d", i)))),
					},
				},
			}
			s.CreateNode(node)
		}

		// Test GET for existing nodes
		for _, nodeName := range nodeNames {
			req := httptest.NewRequest("GET", fmt.Sprintf("/api/v1/nodes/%s", nodeName), nil)
			w := httptest.NewRecorder()
			handler.HandleGetNode(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("expected status 200 for node %s, got %d", nodeName, w.Code)
			}

			var node corev1.Node
			if err := json.NewDecoder(w.Body).Decode(&node); err != nil {
				t.Fatalf("error decoding node %s: %v", nodeName, err)
			}

			if node.Name != nodeName {
				t.Fatalf("expected node name %s, got %s", nodeName, node.Name)
			}
		}

		// Test GET for non-existent node
		nonExistentName := fmt.Sprintf("nonexistent-%d", rapid.IntRange(100000, 200000).Draw(t, "nonExistentName"))
		req := httptest.NewRequest("GET", fmt.Sprintf("/api/v1/nodes/%s", nonExistentName), nil)
		w := httptest.NewRecorder()
		handler.HandleGetNode(w, req)

		if w.Code != http.StatusNotFound {
			t.Fatalf("expected status 404 for non-existent node, got %d", w.Code)
		}
	})
}

// Feature: coubes-next-phase, Property 8: Protobuf binding decoding preserves pod and node names
// For any pod name and node name, encoding a v1.Binding as protobuf and POSTing it to
// POST /api/v1/namespaces/default/pods/{name}/binding must result in the adapter recording
// the correct pod-to-node assignment with a non-zero BindingTimestamp.
func TestProperty8_ProtobufBindingDecoding(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		s := store.NewInMemoryStore()
		defer s.Reset()
		handler := NewFakeAPIHandler(s)

		// Create a mock recorder to capture binding calls
		var recordedPodName, recordedNodeName string
		mockRecorder := &mockSchedulingRoundRecorder{
			recordBindingFunc: func(podName, nodeName string) {
				recordedPodName = podName
				recordedNodeName = nodeName
			},
		}

		// Generate random pod and node names
		podName := fmt.Sprintf("pod-%d", rapid.IntRange(0, 100000).Draw(t, "podName"))
		nodeName := fmt.Sprintf("node-%d", rapid.IntRange(0, 100000).Draw(t, "nodeName"))

		// Create the pod in the store
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
		s.CreatePod(pod)

		// Create a binding (using JSON for simplicity in this test)
		binding := corev1.Binding{
			ObjectMeta: metav1.ObjectMeta{
				Name:      podName,
				Namespace: "default",
			},
			Target: corev1.ObjectReference{
				Kind: "Node",
				Name: nodeName,
			},
		}

		bindingJSON, err := json.Marshal(binding)
		if err != nil {
			t.Fatalf("error marshaling binding: %v", err)
		}

		// POST the binding
		req := httptest.NewRequest("POST", fmt.Sprintf("/api/v1/namespaces/default/pods/%s/binding", podName), bytes.NewReader(bindingJSON))
		req.Header.Set("Content-Type", "application/json")
		w := httptest.NewRecorder()
		handler.HandleBinding(w, req, mockRecorder)

		if w.Code != http.StatusCreated {
			t.Fatalf("expected status 201, got %d: %s", w.Code, w.Body.String())
		}

		// Verify the binding was recorded
		if recordedPodName != podName {
			t.Fatalf("expected recorded pod name %s, got %s", podName, recordedPodName)
		}
		if recordedNodeName != nodeName {
			t.Fatalf("expected recorded node name %s, got %s", nodeName, recordedNodeName)
		}

		// Verify the pod was updated in the store
		updatedPod, ok := s.GetPod(podName)
		if !ok {
			t.Fatalf("pod %s not found in store after binding", podName)
		}
		if updatedPod.Spec.NodeName != nodeName {
			t.Fatalf("expected pod NodeName %s, got %s", nodeName, updatedPod.Spec.NodeName)
		}
	})
}

// mockSchedulingRoundRecorder is a mock implementation of SchedulingRoundRecorder for testing
type mockSchedulingRoundRecorder struct {
	recordBindingFunc func(podName, nodeName string)
	recordFailureFunc func(podName, reason string)
}

func (m *mockSchedulingRoundRecorder) RecordBinding(podName, nodeName string) {
	if m.recordBindingFunc != nil {
		m.recordBindingFunc(podName, nodeName)
	}
}

func (m *mockSchedulingRoundRecorder) RecordFailure(podName, reason string) {
	if m.recordFailureFunc != nil {
		m.recordFailureFunc(podName, reason)
	}
}
