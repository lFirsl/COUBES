package communicator_test

import (
	"bytes"
	"encoding/json"
	"io"
	"net/http"
	"testing"
	"time"
)

// Note - this test is old and may no longer function as intended.
// todo: Update Tests

type CsNode struct {
	ID       int    `json:"id"`            // Unique identifier for the node (CloudSim VM/Container ID)
	Name     string `json:"name"`          // Name of the node (e.g., "vm-0", "container-1")
	MIPSAval int    `json:"mipsAvailable"` // Available MIPS on this node
	RAMAval  int    `json:"ramAvailable"`  // Available RAM on this node (in MB)
}

// CsPod represents a simulated Kubernetes CsPod (which maps to a CloudSim Cloudlet).
type CsPod struct {
	ID       int    `json:"id"`                 // Unique identifier for the pod (CloudSim Cloudlet ID)
	Name     string `json:"name"`               // Name of the pod (e.g., "cloudlet-0")
	MIPSReq  int    `json:"mipsRequested"`      // MIPS requested by this pod
	RAMReq   int    `json:"ramRequested"`       // RAM requested by this pod (in MB)
	Status   string `json:"status"`             // Current status of the pod ("Pending", "Scheduled", "Unschedulable")
	NodeName string `json:"nodeName,omitempty"` // The name of the node it's scheduled on (empty if not scheduled)
	NodeID   int    `json:"vmId"`               // The CloudSim VM/Container ID it's scheduled on.
	// `omitempty` removed to ensure vmId is always present in JSON, even if 0.
	SchedulerName string `json:"schedulerName,omitempty"` // Example: if you use a specific scheduler name for this pod
}

// --- Test ---
func TestSendAndSchedulePods(t *testing.T) {
	baseURL := "http://localhost:8080"

	// --- Cleanup before test ---
	deleteAll(t, baseURL+"/pods/delete-all")
	deleteAll(t, baseURL+"/nodes/delete-all")

	// --- Input ---
	nodes := []CsNode{
		{ID: 1, Name: "vm-1", MIPSAval: 4000, RAMAval: 8192},
		{ID: 2, Name: "vm-2", MIPSAval: 2000, RAMAval: 4096},
	}
	pods := []CsPod{
		{ID: 1, Name: "cloudlet-1", MIPSReq: 1000, RAMReq: 1024},
		{ID: 2, Name: "cloudlet-2", MIPSReq: 2000, RAMReq: 2048},
	}

	// --- Send Nodes ---
	sendJSON(t, baseURL+"/nodes", nodes)

	// --- Wait a moment for them to register ---
	time.Sleep(1 * time.Second)

	// --- Send Pods ---
	resp := sendJSON(t, baseURL+"/schedule-pods", pods)
	defer resp.Body.Close()

	// --- Decode result ---
	var result []CsPod
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &result); err != nil {
		t.Fatalf("Failed to decode response body:\n%s\nError: %v", string(body), err)
	}

	// --- Validate ---
	for _, p := range result {
		if p.Status != "Scheduled" && p.Status != "Running" {
			t.Errorf("Pod %d (%s) was not scheduled (status: %s)", p.ID, p.Name, p.Status)
		}
		if p.NodeName == "" {
			t.Errorf("Pod %d is missing NodeName", p.ID)
		}
	}

	// --- Cleanup after test ---
	deleteAll(t, baseURL+"/pods/delete-all")
	deleteAll(t, baseURL+"/nodes/delete-all")
}

// --- Helpers ---

func deleteAll(t *testing.T, url string) {
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
