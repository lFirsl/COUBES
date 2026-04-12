package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"k8s-cloudsim-adapter/communicator"
	"k8s-cloudsim-adapter/fakeapi"
	"k8s-cloudsim-adapter/scheduler"
	"k8s-cloudsim-adapter/store"
)

// Feature: coubes-next-phase
// Integration tests for key adapter behaviors

func TestZeroPodSnapshotReturnsEmptyBatchDecision(t *testing.T) {
	// Zero-pod SimulationSnapshot returns empty BatchDecision immediately (no scheduler contact)
	store := store.NewInMemoryStore()
	round := scheduler.NewSchedulingRound(60 * time.Second)
	comm := communicator.NewCommunicator(store, round, "default-scheduler", false)

	snapshot := communicator.SimulationSnapshot{
		Nodes:           []communicator.CsNode{},
		Pods:            []communicator.CsPod{},
		CompletedPodIDs: []int{},
	}

	body, _ := json.Marshal(snapshot)
	req := httptest.NewRequest("POST", "/schedule", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	comm.HandleSchedule(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}

	var response communicator.BatchDecision
	json.Unmarshal(w.Body.Bytes(), &response)

	if len(response.Scheduled) != 0 || len(response.Unschedulable) != 0 {
		t.Errorf("Expected empty BatchDecision, got %+v", response)
	}
}

func TestSchedulingTimeoutReturnsHTTP408(t *testing.T) {
	// Scheduling timeout returns HTTP 408
	store := store.NewInMemoryStore()
	round := scheduler.NewSchedulingRound(100 * time.Millisecond) // Very short timeout
	comm := communicator.NewCommunicator(store, round, "default-scheduler", false)

	pods := []communicator.CsPod{
		{ID: 1, Name: "test-pod", Pes: 1},
	}

	body, _ := json.Marshal(pods)
	req := httptest.NewRequest("POST", "/schedule-pods", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()

	comm.HandleSchedulePods(w, req)

	if w.Code != http.StatusRequestTimeout {
		t.Errorf("Expected status 408, got %d", w.Code)
	}
}

func TestConcurrentSchedulePodsReturnsHTTP409(t *testing.T) {
	// Concurrent POST /schedule-pods while round active returns HTTP 409
	store := store.NewInMemoryStore()
	round := scheduler.NewSchedulingRound(60 * time.Second)
	comm := communicator.NewCommunicator(store, round, "default-scheduler", false)

	pods := []communicator.CsPod{
		{ID: 1, Name: "test-pod", Pes: 1},
	}

	body, _ := json.Marshal(pods)

	// Start first request (will block)
	req1 := httptest.NewRequest("POST", "/schedule-pods", bytes.NewReader(body))
	req1.Header.Set("Content-Type", "application/json")
	w1 := httptest.NewRecorder()

	go comm.HandleSchedulePods(w1, req1)

	// Give first request time to start
	time.Sleep(10 * time.Millisecond)

	// Start second request (should get 409)
	req2 := httptest.NewRequest("POST", "/schedule-pods", bytes.NewReader(body))
	req2.Header.Set("Content-Type", "application/json")
	w2 := httptest.NewRecorder()

	comm.HandleSchedulePods(w2, req2)

	if w2.Code != http.StatusConflict {
		t.Errorf("Expected status 409, got %d", w2.Code)
	}

	// Clean up by resetting the round
	round.Reset()
}

func TestRemovedEndpointReturnsHTTP404(t *testing.T) {
	// POST /pods/update-state returns HTTP 404 (endpoint removed)
	store := store.NewInMemoryStore()
	round := scheduler.NewSchedulingRound(60 * time.Second)
	comm := communicator.NewCommunicator(store, round, "default-scheduler", false)

	req := httptest.NewRequest("POST", "/pods/update-state", nil)
	w := httptest.NewRecorder()

	// Create a simple router to test the missing endpoint
	mux := http.NewServeMux()
	mux.HandleFunc("/schedule", comm.HandleSchedule)
	mux.HandleFunc("/nodes", comm.HandleNodes)
	mux.HandleFunc("/schedule-pods", comm.HandleSchedulePods)
	mux.HandleFunc("/reset", comm.HandleReset)
	mux.HandleFunc("/pods/", comm.HandlePodStatus)

	mux.ServeHTTP(w, req)

	// The endpoint is removed, so we expect either 404 or 405
	if w.Code != http.StatusNotFound && w.Code != http.StatusMethodNotAllowed {
		t.Errorf("Expected status 404 or 405, got %d", w.Code)
	}
}

func TestNamespacesEndpointContainsDefault(t *testing.T) {
	// GET /api/v1/namespaces response contains default
	store := store.NewInMemoryStore()
	handler := fakeapi.NewFakeAPIHandler(store)

	req := httptest.NewRequest("GET", "/api/v1/namespaces", nil)
	w := httptest.NewRecorder()

	handler.HandleListNamespaces(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Expected status 200, got %d", w.Code)
	}

	var response map[string]interface{}
	json.Unmarshal(w.Body.Bytes(), &response)

	items, ok := response["items"].([]interface{})
	if !ok || len(items) == 0 {
		t.Errorf("Expected at least one namespace, got %+v", response)
	}

	// Check that default namespace is present
	found := false
	for _, item := range items {
		if ns, ok := item.(map[string]interface{}); ok {
			if metadata, ok := ns["metadata"].(map[string]interface{}); ok {
				if name, ok := metadata["name"].(string); ok && name == "default" {
					found = true
					break
				}
			}
		}
	}

	if !found {
		t.Errorf("Default namespace not found in response: %+v", response)
	}
}

func TestResetFollowedByGetNodesReturnsEmptyList(t *testing.T) {
	// DELETE /reset followed by GET /api/v1/nodes returns empty list
	store := store.NewInMemoryStore()
	round := scheduler.NewSchedulingRound(60 * time.Second)
	comm := communicator.NewCommunicator(store, round, "default-scheduler", false)
	handler := fakeapi.NewFakeAPIHandler(store)

	// Add some nodes first
	nodes := []communicator.CsNode{
		{ID: 1, Name: "node1", Pes: 2, RAMAval: 1024},
	}
	body, _ := json.Marshal(nodes)
	req := httptest.NewRequest("POST", "/nodes", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	comm.HandleNodes(w, req)

	// Verify nodes were added
	req = httptest.NewRequest("GET", "/api/v1/nodes", nil)
	w = httptest.NewRecorder()
	handler.HandleListNodes(w, req)

	var response map[string]interface{}
	json.Unmarshal(w.Body.Bytes(), &response)
	items := response["items"].([]interface{})
	if len(items) == 0 {
		t.Errorf("Expected nodes to be present before reset")
	}

	// Reset
	req = httptest.NewRequest("DELETE", "/reset", nil)
	w = httptest.NewRecorder()
	comm.HandleReset(w, req)

	if w.Code != http.StatusOK {
		t.Errorf("Reset failed with status %d", w.Code)
	}

	// Check nodes are empty after reset
	req = httptest.NewRequest("GET", "/api/v1/nodes", nil)
	w = httptest.NewRecorder()
	handler.HandleListNodes(w, req)

	json.Unmarshal(w.Body.Bytes(), &response)
	items = response["items"].([]interface{})
	if len(items) != 0 {
		t.Errorf("Expected empty node list after reset, got %d items", len(items))
	}
}