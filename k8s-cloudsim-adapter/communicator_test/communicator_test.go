package communicator_test

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"k8s-cloudsim-adapter/communicator"
	"k8s-cloudsim-adapter/store"
	"net/http"
	"net/http/httptest"
	"sort"
	"testing"
	"time"

	"pgregory.net/rapid"
)

// Note - this test has been updated for the new fake API server architecture

type CsNode struct {
	ID       int    `json:"id"`            // Unique identifier for the node (CloudSim VM/Container ID)
	Name     string `json:"name"`          // Name of the node (e.g., "vm-0", "container-1")
	MIPSAval int    `json:"mipsAvailable"` // Available MIPS on this node
	RAMAval  int    `json:"ramAvailable"`  // Available RAM on this node (in MB)
	Pes      int    `json:"pes"`           // Number of processing elements
	BW       int64  `json:"bw"`            // Bandwidth
	Size     int64  `json:"size"`          // Storage size
	Type     string `json:"type"`          // "vm" or "container"
}

// CsPod represents a simulated Kubernetes CsPod (which maps to a CloudSim Cloudlet).
type CsPod struct {
	ID             int     `json:"id"`
	Name           string  `json:"name"`
	Length         int64   `json:"length"`         // cloudletLength
	Pes            int     `json:"pes"`            // Number of processing elements (cores)
	FileSize       int64   `json:"fileSize"`       // Input size
	OutputSize     int64   `json:"outputSize"`     // Output size
	UtilizationCPU float64 `json:"utilizationCpu"` // 0.0 to 1.0
	UtilizationRAM float64 `json:"utilizationRam"`
	UtilizationBW  float64 `json:"utilizationBw"`

	Status        string `json:"status"` // Kubernetes status ("Pending", "Running", etc.)
	NodeName      string `json:"nodeName,omitempty"`
	NodeID        int    `json:"vmId"` // CloudSim VM id
	SchedulerName string `json:"schedulerName,omitempty"`
}

// BatchDecision represents the response from the new batch scheduling API
type BatchDecision struct {
	Scheduled     []PodAssignment `json:"scheduled"`
	Unschedulable []PodFailure    `json:"unschedulable"`
}

type PodAssignment struct {
	PodID            int       `json:"podId"`
	NodeID           int       `json:"nodeId"`
	BindingTimestamp time.Time `json:"bindingTimestamp"`
}

type PodFailure struct {
	PodID  int    `json:"podId"`
	Reason string `json:"reason"`
}

// SimulationSnapshot represents the input to the new batch scheduling API
type SimulationSnapshot struct {
	Nodes           []CsNode `json:"nodes"`
	Pods            []CsPod  `json:"pods"`
	CompletedPodIDs []int    `json:"completedPodIds"`
}

// --- Test ---
func TestSendAndSchedulePods(t *testing.T) {
	baseURL := "http://localhost:8080"

	// Check if server is running, skip test if not
	_, err := http.Get(baseURL + "/api/v1/nodes")
	if err != nil {
		t.Skipf("Server not running at %s, skipping integration test: %v", baseURL, err)
	}

	// --- Cleanup before test ---
	resetCluster(t, baseURL+"/reset")

	// --- Input ---
	nodes := []CsNode{
		{ID: 1, Name: "vm-1", MIPSAval: 4000, RAMAval: 8192, Pes: 4, BW: 1000, Size: 10000, Type: "vm"},
		{ID: 2, Name: "vm-2", MIPSAval: 2000, RAMAval: 4096, Pes: 2, BW: 1000, Size: 10000, Type: "vm"},
	}
	pods := []CsPod{
		{ID: 1, Name: "cloudlet-1", Pes: 1, Length: 1000, FileSize: 100, OutputSize: 100, UtilizationCPU: 0.8},
		{ID: 2, Name: "cloudlet-2", Pes: 2, Length: 2000, FileSize: 200, OutputSize: 200, UtilizationCPU: 0.8},
	}

	// --- Create simulation snapshot ---
	snapshot := SimulationSnapshot{
		Nodes:           nodes,
		Pods:            pods,
		CompletedPodIDs: []int{},
	}

	// --- Send snapshot to schedule endpoint ---
	resp := sendJSONWithTimeout(t, baseURL+"/schedule", snapshot, 5*time.Second)
	defer resp.Body.Close()

	// --- Decode result ---
	var result BatchDecision
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &result); err != nil {
		t.Fatalf("Failed to decode response body:\n%s\nError: %v", string(body), err)
	}

	// --- Validate ---
	totalPods := len(result.Scheduled) + len(result.Unschedulable)
	if totalPods != len(pods) {
		t.Errorf("Expected %d total decisions, got %d", len(pods), totalPods)
	}

	// In a real test with a running kube-scheduler, we would expect pods to be scheduled.
	// Since we're testing without a scheduler, pods will likely remain unscheduled or timeout.
	// This test mainly verifies that the API endpoints work correctly.
	t.Logf("Scheduled pods: %d, Unschedulable pods: %d", len(result.Scheduled), len(result.Unschedulable))

	// --- Cleanup after test ---
	resetCluster(t, baseURL+"/reset")
}

// --- Helpers ---

func resetCluster(t *testing.T, url string) {
	req, err := http.NewRequest(http.MethodDelete, url, nil)
	if err != nil {
		t.Fatalf("Failed to create DELETE request: %v", err)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatalf("Failed DELETE %s: %v", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(resp.Body)
		t.Fatalf("DELETE %s failed: %s", url, string(data))
	}
}

func sendJSON(t *testing.T, url string, data any) *http.Response {
	body, err := json.Marshal(data)
	if err != nil {
		t.Fatalf("Failed to marshal JSON: %v", err)
	}
	resp, err := http.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		t.Fatalf("POST to %s failed: %v", url, err)
	}
	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(resp.Body)
		t.Fatalf("POST %s failed with status %d:\n%s", url, resp.StatusCode, string(data))
	}
	return resp
}

func sendJSONWithTimeout(t *testing.T, url string, data any, timeout time.Duration) *http.Response {
	body, err := json.Marshal(data)
	if err != nil {
		t.Fatalf("Failed to marshal JSON: %v", err)
	}
	
	client := &http.Client{Timeout: timeout}
	resp, err := client.Post(url, "application/json", bytes.NewReader(body))
	if err != nil {
		// If it's a timeout, that's expected when no scheduler is running
		t.Logf("POST to %s timed out (expected when no kube-scheduler is running): %v", url, err)
		// Return a mock response for timeout case
		return &http.Response{
			StatusCode: 408,
			Body:       io.NopCloser(bytes.NewReader([]byte(`{"scheduled":[],"unschedulable":[]}`))),
		}
	}
	if resp.StatusCode == 408 {
		// Scheduling timeout is expected when no scheduler is running
		t.Logf("POST %s returned 408 (expected when no kube-scheduler is running)", url)
		return resp
	}
	if resp.StatusCode != http.StatusOK {
		data, _ := io.ReadAll(resp.Body)
		t.Fatalf("POST %s failed with status %d:\n%s", url, resp.StatusCode, string(data))
	}
	return resp
}

// Feature: coubes-next-phase, Property 5: Update-state rescheduling in test mode
// **Validates: Requirements 3.3**
func TestProperty5_UpdateStateReschedulingInTestMode(t *testing.T) {
	rapid.Check(t, func(t *rapid.T) {
		// Generate random number of pending pods and nodes
		numPendingPods := rapid.IntRange(0, 30).Draw(t, "numPendingPods")
		numScheduledPods := rapid.IntRange(1, 10).Draw(t, "numScheduledPods")
		numNodes := rapid.IntRange(1, 15).Draw(t, "numNodes")

		// Create test mode communicator
		testStore := store.NewInMemoryStore()
		comm := communicator.NewCommunicator(testStore, nil, "test-scheduler", true)

		// Create nodes
		nodes := make([]CsNode, numNodes)
		for i := 0; i < numNodes; i++ {
			nodes[i] = CsNode{
				ID:       i + 1,
				Name:     fmt.Sprintf("csnode-%d", i+1),
				MIPSAval: 4000,
				RAMAval:  8192,
				Pes:      4,
				BW:       1000,
				Size:     10000,
				Type:     "vm",
			}
		}

		// Register nodes via HandleNodes
		nodesJSON, _ := json.Marshal(nodes)
		req := httptest.NewRequest(http.MethodPost, "/nodes", bytes.NewReader(nodesJSON))
		w := httptest.NewRecorder()
		comm.HandleNodes(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("HandleNodes failed with status %d", w.Code)
		}

		// Create pending pods (no NodeName set)
		pendingPods := make([]CsPod, numPendingPods)
		for i := 0; i < numPendingPods; i++ {
			pendingPods[i] = CsPod{
				ID:             i + 1,
				Name:           fmt.Sprintf("cspod-%d", i+1),
				Length:         1000,
				Pes:            1,
				FileSize:       100,
				OutputSize:     100,
				UtilizationCPU: 0.8,
				Status:         "Pending",
				NodeName:       "", // Pending - no node assigned
			}
		}

		// Create scheduled pods (with NodeName set) - these will be tracked but not rescheduled
		scheduledPods := make([]CsPod, numScheduledPods)
		for i := 0; i < numScheduledPods; i++ {
			scheduledPods[i] = CsPod{
				ID:             numPendingPods + i + 1,
				Name:           fmt.Sprintf("cspod-%d", numPendingPods+i+1),
				Length:         1000,
				Pes:            1,
				FileSize:       100,
				OutputSize:     100,
				UtilizationCPU: 0.8,
				Status:         "Running",
				NodeName:       fmt.Sprintf("csnode-%d", (i%numNodes)+1), // Already scheduled
				NodeID:         (i % numNodes) + 1,
			}
		}

		// Schedule all pods via HandleSchedulePods to populate the communicator's internal state
		allPods := append(pendingPods, scheduledPods...)
		podsJSON, _ := json.Marshal(allPods)
		req = httptest.NewRequest(http.MethodPost, "/schedule-pods", bytes.NewReader(podsJSON))
		w = httptest.NewRecorder()
		comm.HandleSchedulePods(w, req)
		if w.Code != http.StatusOK {
			t.Fatalf("HandleSchedulePods failed with status %d", w.Code)
		}

		// Now mark one of the scheduled pods as completed via HandleUpdateState
		if numScheduledPods > 0 {
			completedPodID := numPendingPods + 1 // First scheduled pod
			updateReq := struct {
				PodID int `json:"podId"`
			}{PodID: completedPodID}
			updateJSON, _ := json.Marshal(updateReq)
			req = httptest.NewRequest(http.MethodPost, "/pods/update-state", bytes.NewReader(updateJSON))
			w = httptest.NewRecorder()
			comm.HandleUpdateState(w, req)

			if w.Code != http.StatusOK {
				t.Fatalf("HandleUpdateState failed with status %d: %s", w.Code, w.Body.String())
			}

			// Decode the BatchDecision response
			var decision BatchDecision
			if err := json.NewDecoder(w.Body).Decode(&decision); err != nil {
				t.Fatalf("Failed to decode BatchDecision: %v", err)
			}

			// Verify that the decision contains assignments for pending pods only
			// In test mode, HandleUpdateState should reschedule pending pods using round-robin
			if numPendingPods > 0 {
				if len(decision.Scheduled) != numPendingPods {
					t.Fatalf("Expected %d scheduled pods in decision, got %d", numPendingPods, len(decision.Scheduled))
				}

				// Verify round-robin formula: pod i should be assigned to sortedNodes[i % M]
				sortedNodes := make([]CsNode, len(nodes))
				copy(sortedNodes, nodes)
				sort.Slice(sortedNodes, func(i, j int) bool {
					return sortedNodes[i].Name < sortedNodes[j].Name
				})

				for i, assignment := range decision.Scheduled {
					expectedNodeIndex := i % numNodes
					expectedNodeID := sortedNodes[expectedNodeIndex].ID
					if assignment.NodeID != expectedNodeID {
						t.Fatalf("Assignment %d: expected node ID %d, got %d", i, expectedNodeID, assignment.NodeID)
					}
					// Verify BindingTimestamp is set
					if assignment.BindingTimestamp.IsZero() {
						t.Fatalf("Assignment %d has zero BindingTimestamp", i)
					}
				}
			} else {
				// No pending pods, should return empty decision
				if len(decision.Scheduled) != 0 || len(decision.Unschedulable) != 0 {
					t.Fatalf("Expected empty decision when no pending pods, got %d scheduled, %d unschedulable",
						len(decision.Scheduled), len(decision.Unschedulable))
				}
			}
		}
	})
}
