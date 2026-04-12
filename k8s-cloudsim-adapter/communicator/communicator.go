package communicator

import (
	"context"
	"encoding/json"
	"fmt"
	"k8s-cloudsim-adapter/scheduler"
	"k8s-cloudsim-adapter/store"
	"k8s-cloudsim-adapter/utils"
	corev1 "k8s.io/api/core/v1"
	"log"
	"net/http"
	"strconv"
	"sync"
)

// Communicator handles simulation-facing HTTP endpoints.
// It is wired to InMemoryStore and SchedulingRound instead of KubeClient.
type Communicator struct {
	store         *store.InMemoryStore
	round         *scheduler.SchedulingRound
	schedulerName string
	mu            sync.RWMutex
	pods          map[int]*CsPod // Local tracking for pod status endpoint
	testMode      bool
	testSched     *scheduler.TestModeScheduler // nil in normal mode
}

// NewCommunicator creates a new Communicator with the given store, scheduling round, and scheduler name.
// When testMode is true, testSched is initialised and round is ignored (can be nil).
func NewCommunicator(store *store.InMemoryStore, round *scheduler.SchedulingRound, schedulerName string, testMode bool) *Communicator {
	comm := &Communicator{
		store:         store,
		round:         round,
		schedulerName: schedulerName,
		pods:          make(map[int]*CsPod),
		testMode:      testMode,
	}
	
	if testMode {
		comm.testSched = &scheduler.TestModeScheduler{}
	}
	
	return comm
}

// HandleNodes processes node sync requests from CloudSim.
// Decodes []CsNode and applies node diff against store (add missing, remove stale).
func (c *Communicator) HandleNodes(w http.ResponseWriter, r *http.Request) {
	log.Printf("Starting HandleNodes()")
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	var incomingNodes []CsNode
	if err := json.NewDecoder(r.Body).Decode(&incomingNodes); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Apply node diff
	if err := c.applyNodeDiff(incomingNodes); err != nil {
		http.Error(w, "Failed to apply node diff: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "Synced %d nodes\n", len(incomingNodes))
}

// HandleSchedule processes POST /schedule requests.
// Decodes SimulationSnapshot, applies node diff, deletes completed pods, schedules pending pods.
func (c *Communicator) HandleSchedule(w http.ResponseWriter, r *http.Request) {
	log.Printf("Starting HandleSchedule()")
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	var snapshot SimulationSnapshot
	if err := json.NewDecoder(r.Body).Decode(&snapshot); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Step 1: Apply node diff
	if err := c.applyNodeDiff(snapshot.Nodes); err != nil {
		http.Error(w, "Failed to apply node diff: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// Step 2: Delete completed pods from store
	for _, podID := range snapshot.CompletedPodIDs {
		podName := fmt.Sprintf("cspod-%d", podID)
		c.store.DeletePod(podName)
		
		// Also remove from local tracking
		c.mu.Lock()
		delete(c.pods, podID)
		c.mu.Unlock()
	}

	// Step 3: If no pending pods, return empty BatchDecision immediately
	if len(snapshot.Pods) == 0 {
		decision := BatchDecision{
			Scheduled:     []PodAssignment{},
			Unschedulable: []PodFailure{},
		}
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(decision)
		return
	}

	// Step 4: Create pods in store and begin scheduling round
	for _, csPod := range snapshot.Pods {
		pod := BuildPod(csPod, c.schedulerName)
		c.store.CreatePod(pod)
		
		// Track in local memory for status endpoint
		c.mu.Lock()
		podCopy := csPod
		if podCopy.Status == "" {
			podCopy.Status = "Pending"
		}
		c.pods[csPod.ID] = &podCopy
		c.mu.Unlock()
	}

	// Step 5: Branch on test mode
	var decision scheduler.BatchDecision
	
	if c.testMode {
		// Test mode: call TestModeScheduler synchronously
		csNodes := c.store.GetNodes()
		nodes := make([]scheduler.SchedulerNode, len(csNodes))
		for i, node := range csNodes {
			nodeID, err := extractNodeID(node.Name)
			if err != nil {
				nodeID = -1 // fallback for invalid node names
			}
			nodes[i] = scheduler.SchedulerNode{
				ID:   nodeID,
				Name: node.Name,
			}
		}
		
		pods := make([]scheduler.SchedulerPod, len(snapshot.Pods))
		for i, csPod := range snapshot.Pods {
			pods[i] = scheduler.SchedulerPod{
				ID:   csPod.ID,
				Name: csPod.Name,
			}
		}
		
		decision = c.testSched.Schedule(pods, nodes)
	} else {
		// Normal mode: use SchedulingRound
		if err := c.round.Begin(len(snapshot.Pods)); err != nil {
			http.Error(w, err.Error(), http.StatusConflict) // HTTP 409 if round already active
			return
		}

		ctx := context.Background()
		var err error
		decision, err = c.round.Wait(ctx)
		if err != nil {
			http.Error(w, err.Error(), http.StatusRequestTimeout) // HTTP 408 on timeout
			return
		}
	}

	// Step 6: Return BatchDecision
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(decision)
}

// HandleSchedulePods processes POST /schedule-pods requests.
// Decodes []CsPod, creates pods in store, begins scheduling round, returns BatchDecision.
// In test mode, calls TestModeScheduler synchronously; in normal mode, uses SchedulingRound.
func (c *Communicator) HandleSchedulePods(w http.ResponseWriter, r *http.Request) {
	log.Printf("Starting HandleSchedulePods()")
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	var csPods []CsPod
	if err := json.NewDecoder(r.Body).Decode(&csPods); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Step 1: Create pods in store
	for _, csPod := range csPods {
		pod := BuildPod(csPod, c.schedulerName)
		c.store.CreatePod(pod)
		
		// Track in local memory for status endpoint
		c.mu.Lock()
		podCopy := csPod
		if podCopy.Status == "" {
			podCopy.Status = "Pending"
		}
		c.pods[csPod.ID] = &podCopy
		c.mu.Unlock()
	}

	// Step 2: Branch on test mode
	var decision scheduler.BatchDecision
	
	if c.testMode {
		// Test mode: call TestModeScheduler synchronously
		csNodes := c.store.GetNodes()
		nodes := make([]scheduler.SchedulerNode, len(csNodes))
		for i, node := range csNodes {
			nodeID, err := extractNodeID(node.Name)
			if err != nil {
				nodeID = -1 // fallback for invalid node names
			}
			nodes[i] = scheduler.SchedulerNode{
				ID:   nodeID,
				Name: node.Name,
			}
		}
		
		pods := make([]scheduler.SchedulerPod, len(csPods))
		for i, csPod := range csPods {
			pods[i] = scheduler.SchedulerPod{
				ID:   csPod.ID,
				Name: csPod.Name,
			}
		}
		
		decision = c.testSched.Schedule(pods, nodes)
	} else {
		// Normal mode: use SchedulingRound
		if err := c.round.Begin(len(csPods)); err != nil {
			http.Error(w, err.Error(), http.StatusConflict) // HTTP 409 if round already active
			return
		}

		ctx := context.Background()
		var err error
		decision, err = c.round.Wait(ctx)
		if err != nil {
			http.Error(w, err.Error(), http.StatusRequestTimeout) // HTTP 408 on timeout
			return
		}
	}

	// Step 3: Return BatchDecision
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(decision)
}

// HandleUpdateState processes POST /pods/update-state requests.
// Decodes request body containing the completed pod ID, deletes the pod from store and local tracking.
// In test mode: collects pending pods (pods with no nodeName), calls TestModeScheduler.Schedule, returns BatchDecision.
// In normal mode: returns HTTP 200 with empty BatchDecision (no external scheduler interaction needed).
// Returns HTTP 404 if pod ID not found.
func (c *Communicator) HandleUpdateState(w http.ResponseWriter, r *http.Request) {
	log.Printf("Starting HandleUpdateState()")
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	// Decode request body containing completed pod ID
	var req struct {
		PodID int `json:"podId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Check if pod exists
	c.mu.RLock()
	_, exists := c.pods[req.PodID]
	c.mu.RUnlock()
	
	if !exists {
		http.Error(w, "Pod not found", http.StatusNotFound)
		return
	}

	// Delete the pod from store and local tracking
	podName := fmt.Sprintf("cspod-%d", req.PodID)
	c.store.DeletePod(podName)
	
	c.mu.Lock()
	delete(c.pods, req.PodID)
	c.mu.Unlock()

	// Branch on test mode
	var decision scheduler.BatchDecision
	
	if c.testMode {
		// Test mode: collect pending pods and reschedule
		c.mu.RLock()
		var pendingPods []scheduler.SchedulerPod
		for id, pod := range c.pods {
			if pod.NodeName == "" {
				pendingPods = append(pendingPods, scheduler.SchedulerPod{
					ID:   id,
					Name: pod.Name,
				})
			}
		}
		c.mu.RUnlock()
		
		// Get nodes from store
		csNodes := c.store.GetNodes()
		nodes := make([]scheduler.SchedulerNode, len(csNodes))
		for i, node := range csNodes {
			nodeID, err := extractNodeID(node.Name)
			if err != nil {
				nodeID = -1 // fallback for invalid node names
			}
			nodes[i] = scheduler.SchedulerNode{
				ID:   nodeID,
				Name: node.Name,
			}
		}
		
		// Schedule pending pods
		decision = c.testSched.Schedule(pendingPods, nodes)
	} else {
		// Normal mode: return empty BatchDecision
		decision = scheduler.BatchDecision{
			Scheduled:     []scheduler.PodAssignment{},
			Unschedulable: []scheduler.PodFailure{},
		}
	}

	// Return BatchDecision
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(decision)
}

// HandleReset processes DELETE /reset requests.
// Calls round.Reset() then store.Reset().
func (c *Communicator) HandleReset(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "Only DELETE allowed", http.StatusMethodNotAllowed)
		return
	}

	// Reset scheduling round (only in normal mode)
	if !c.testMode && c.round != nil {
		c.round.Reset()
	}

	// Emit DELETED events for all pods and nodes before resetting,
	// so the kube-scheduler's internal cache is properly cleared.
	c.store.DeleteAll()

	// Reset store (recreates channels/broadcasters)
	c.store.Reset()

	// Clear local pod tracking
	c.mu.Lock()
	c.pods = make(map[int]*CsPod)
	c.mu.Unlock()

	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "Reset complete\n")
}

// HandlePodStatus processes GET /pods/{id}/status requests.
// Looks up pod in store by CloudSim ID and returns CsPod JSON or HTTP 404.
func (c *Communicator) HandlePodStatus(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		http.Error(w, "Only GET method is allowed", http.StatusMethodNotAllowed)
		return
	}

	pathSegments := utils.SplitPath(r.URL.Path)
	if len(pathSegments) < 3 || pathSegments[len(pathSegments)-1] != "status" || pathSegments[len(pathSegments)-3] != "pods" {
		http.Error(w, "Invalid URL path. Expected /pods/{id}/status", http.StatusBadRequest)
		return
	}

	podIDStr := pathSegments[len(pathSegments)-2]
	podID, err := strconv.Atoi(podIDStr)
	if err != nil {
		http.Error(w, "Invalid CsPod ID", http.StatusBadRequest)
		return
	}

	c.mu.RLock()
	pod, exists := c.pods[podID]
	c.mu.RUnlock()
	
	if !exists {
		http.Error(w, "CsPod not found", http.StatusNotFound)
		return
	}
	
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(pod)
}

// applyNodeDiff applies a node diff against the InMemoryStore.
// Adds missing nodes and removes stale nodes to match the incoming list exactly.
func (c *Communicator) applyNodeDiff(incomingNodes []CsNode) error {
	// Step 1: Get current nodes from store
	currentNodes := c.store.GetNodes()
	currentMap := make(map[int]*corev1.Node)
	for _, node := range currentNodes {
		if id, err := extractNodeID(node.Name); err == nil {
			currentMap[id] = node
		}
	}

	// Step 2: Build incoming map
	incomingMap := make(map[int]CsNode)
	for _, node := range incomingNodes {
		incomingMap[node.ID] = node
	}

	// Step 3: Determine deletions (nodes in store but missing from incoming)
	for id, node := range currentMap {
		if _, exists := incomingMap[id]; !exists {
			c.store.DeleteNode(node.Name)
		}
	}

	// Step 4: Determine additions (nodes in incoming but missing from store)
	for id, csNode := range incomingMap {
		if _, exists := currentMap[id]; !exists {
			node := BuildNode(csNode, c.schedulerName)
			c.store.CreateNode(node)
		}
	}

	return nil
}
