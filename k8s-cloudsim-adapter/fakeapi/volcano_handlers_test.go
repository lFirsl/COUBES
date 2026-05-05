package fakeapi

import (
	"bytes"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s-cloudsim-adapter/store"
)

// Test that PUT /pods/{name}/status with an unschedulable condition records the failure.
// This is the Volcano code path — Volcano uses PUT (full replacement) while kube-scheduler uses PATCH.
func TestPodStatusPUT_UnschedulableRecordsFailure(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	handler := NewFakeAPIHandler(s)

	podName := "cspod-5"

	// Create the pod in the store
	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
		Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "c", Image: "img"}}},
		Status:     corev1.PodStatus{Phase: corev1.PodPending},
	}
	s.CreatePod(pod)

	// Build a full pod status update with unschedulable condition (what Volcano sends)
	statusPod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
		Status: corev1.PodStatus{
			Phase: corev1.PodPending,
			Conditions: []corev1.PodCondition{
				{
					Type:    corev1.PodScheduled,
					Status:  corev1.ConditionFalse,
					Reason:  corev1.PodReasonUnschedulable,
					Message: "pod group is not ready, 1 Pending, 1 minAvailable",
				},
			},
		},
	}

	body, _ := json.Marshal(statusPod)

	var recordedPod, recordedReason string
	recorder := &mockSchedulingRoundRecorder{
		recordFailureFunc: func(podName, reason string) {
			recordedPod = podName
			recordedReason = reason
		},
	}

	// PUT (not PATCH) — this is what Volcano does
	req := httptest.NewRequest("PUT", "/api/v1/namespaces/default/pods/"+podName+"/status", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.HandlePodStatusPatch(w, req, recorder)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}

	if recordedPod != podName {
		t.Fatalf("expected failure recorded for %s, got %s", podName, recordedPod)
	}
	if recordedReason == "" {
		t.Fatal("expected non-empty failure reason")
	}
}

// Test that PATCH /pods/{name}/status with an unschedulable condition also works (kube-scheduler path).
func TestPodStatusPATCH_UnschedulableRecordsFailure(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	handler := NewFakeAPIHandler(s)

	podName := "cspod-10"

	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
		Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "c", Image: "img"}}},
		Status:     corev1.PodStatus{Phase: corev1.PodPending},
	}
	s.CreatePod(pod)

	// kube-scheduler sends a strategic merge patch with just the condition
	patch := &corev1.Pod{
		Status: corev1.PodStatus{
			Conditions: []corev1.PodCondition{
				{
					Type:    corev1.PodScheduled,
					Status:  corev1.ConditionFalse,
					Reason:  corev1.PodReasonUnschedulable,
					Message: "0/2 nodes are available: insufficient cpu",
				},
			},
		},
	}

	body, _ := json.Marshal(patch)

	var recordedPod string
	recorder := &mockSchedulingRoundRecorder{
		recordFailureFunc: func(podName, reason string) {
			recordedPod = podName
		},
	}

	req := httptest.NewRequest("PATCH", "/api/v1/namespaces/default/pods/"+podName+"/status", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.HandlePodStatusPatch(w, req, recorder)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}

	if recordedPod != podName {
		t.Fatalf("expected failure recorded for %s, got %s", podName, recordedPod)
	}
}

// Test that a status update without unschedulable condition does NOT record a failure.
func TestPodStatus_NonUnschedulableDoesNotRecordFailure(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	handler := NewFakeAPIHandler(s)

	podName := "cspod-7"

	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{Name: podName, Namespace: "default"},
		Spec:       corev1.PodSpec{Containers: []corev1.Container{{Name: "c", Image: "img"}}},
		Status:     corev1.PodStatus{Phase: corev1.PodPending},
	}
	s.CreatePod(pod)

	// Status update with a different condition (not unschedulable)
	statusPod := &corev1.Pod{
		Status: corev1.PodStatus{
			Phase: corev1.PodRunning,
			Conditions: []corev1.PodCondition{
				{
					Type:   corev1.PodReady,
					Status: corev1.ConditionTrue,
				},
			},
		},
	}

	body, _ := json.Marshal(statusPod)

	failureCalled := false
	recorder := &mockSchedulingRoundRecorder{
		recordFailureFunc: func(podName, reason string) {
			failureCalled = true
		},
	}

	req := httptest.NewRequest("PUT", "/api/v1/namespaces/default/pods/"+podName+"/status", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	handler.HandlePodStatusPatch(w, req, recorder)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d: %s", w.Code, w.Body.String())
	}

	if failureCalled {
		t.Fatal("RecordFailure should not be called for non-unschedulable status")
	}
}

// Test that queue creation and retrieval works in the store.
func TestQueueStore_CreateAndGet(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()

	raw := map[string]interface{}{
		"apiVersion": "scheduling.volcano.sh/v1beta1",
		"kind":       "Queue",
		"metadata":   map[string]interface{}{"name": "high-priority"},
		"spec":       map[string]interface{}{"weight": 3},
		"status":     map[string]interface{}{"state": "Open"},
	}

	s.CreateQueue("high-priority", raw)

	got, exists := s.GetQueue("high-priority")
	if !exists {
		t.Fatal("expected queue to exist after creation")
	}

	spec, ok := got["spec"].(map[string]interface{})
	if !ok {
		t.Fatal("expected spec to be a map")
	}

	weight, ok := spec["weight"]
	if !ok {
		t.Fatal("expected weight field in spec")
	}
	if fmt.Sprintf("%v", weight) != "3" {
		t.Fatalf("expected weight 3, got %v", weight)
	}
}

// Test that GetQueue returns false for non-existent queues.
func TestQueueStore_GetNonExistent(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()

	_, exists := s.GetQueue("does-not-exist")
	if exists {
		t.Fatal("expected non-existent queue to return false")
	}
}

// Test that the Volcano queue list handler returns created queues.
func TestVolcanoListQueues_ReturnsCreatedQueues(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	handler := NewFakeAPIHandler(s)

	// Create two queues
	s.CreateQueue("default", map[string]interface{}{
		"metadata": map[string]interface{}{"name": "default"},
		"spec":     map[string]interface{}{"weight": 1},
	})
	s.CreateQueue("high-priority", map[string]interface{}{
		"metadata": map[string]interface{}{"name": "high-priority"},
		"spec":     map[string]interface{}{"weight": 3},
	})

	req := httptest.NewRequest("GET", "/apis/scheduling.volcano.sh/v1beta1/queues", nil)
	w := httptest.NewRecorder()
	handler.HandleListQueues(w, req)

	if w.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", w.Code)
	}

	var result map[string]interface{}
	if err := json.NewDecoder(w.Body).Decode(&result); err != nil {
		t.Fatalf("error decoding response: %v", err)
	}

	items, ok := result["items"].([]interface{})
	if !ok {
		t.Fatal("expected items array in response")
	}

	if len(items) != 2 {
		t.Fatalf("expected 2 queues, got %d", len(items))
	}
}

// Test that HandleGetQueue returns 404 for non-existent queue (triggers Volcano to create it).
func TestVolcanoGetQueue_Returns404ForNonExistent(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	handler := NewFakeAPIHandler(s)

	req := httptest.NewRequest("GET", "/apis/scheduling.volcano.sh/v1beta1/queues/default", nil)
	w := httptest.NewRecorder()
	handler.HandleGetQueue(w, req)

	if w.Code != http.StatusNotFound {
		t.Fatalf("expected 404, got %d", w.Code)
	}

	// Verify it returns a proper K8s Status object (not plain text 404)
	var status metav1.Status
	if err := json.NewDecoder(w.Body).Decode(&status); err != nil {
		t.Fatalf("expected valid Status JSON, got error: %v", err)
	}

	if status.Reason != metav1.StatusReasonNotFound {
		t.Fatalf("expected reason NotFound, got %s", status.Reason)
	}
}
