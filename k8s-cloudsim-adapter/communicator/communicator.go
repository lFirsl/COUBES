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
	"time"
)

// Communicator handles simulation-facing HTTP endpoints.
type Communicator struct {
	store         *store.InMemoryStore
	round         *scheduler.SchedulingRound
	schedulerName string
	mu            sync.RWMutex
	pods          map[int]*CsPod
	testMode      bool
	testSched     *scheduler.TestModeScheduler
}

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

// logJSON emits a structured JSON log line.
func logJSON(fields map[string]interface{}) {
	fields["ts"] = time.Now().Format(time.RFC3339Nano)
	b, _ := json.Marshal(fields)
	log.Println(string(b))
}

// roundID extracts X-Round-Id header, defaulting to "-".
func roundID(r *http.Request) string {
	if id := r.Header.Get("X-Round-Id"); id != "" {
		return id
	}
	return "-"
}

func (c *Communicator) HandleNodes(w http.ResponseWriter, r *http.Request) {
	t0 := time.Now()
	rid := roundID(r)

	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	var incomingNodes []CsNode
	if err := json.NewDecoder(r.Body).Decode(&incomingNodes); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	if err := c.applyNodeDiff(incomingNodes); err != nil {
		http.Error(w, "Failed to apply node diff: "+err.Error(), http.StatusInternalServerError)
		return
	}

	logJSON(map[string]interface{}{
		"action":   "HandleNodes",
		"roundId":  rid,
		"nodeCount": len(incomingNodes),
		"durationMs": time.Since(t0).Milliseconds(),
		"result":   "ok",
	})

	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "Synced %d nodes\n", len(incomingNodes))
}

func (c *Communicator) HandleSchedule(w http.ResponseWriter, r *http.Request) {
	t0 := time.Now()
	rid := roundID(r)

	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	var snapshot SimulationSnapshot
	if err := json.NewDecoder(r.Body).Decode(&snapshot); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	logJSON(map[string]interface{}{
		"action":       "HandleSchedule",
		"roundId":      rid,
		"podCount":     len(snapshot.Pods),
		"nodeCount":    len(snapshot.Nodes),
		"completedIds": len(snapshot.CompletedPodIDs),
		"phase":        "start",
	})

	// Step 1: Apply node diff
	if err := c.applyNodeDiff(snapshot.Nodes); err != nil {
		http.Error(w, "Failed to apply node diff: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// Step 2: Delete completed pods
	for _, podID := range snapshot.CompletedPodIDs {
		podName := fmt.Sprintf("cspod-%d", podID)
		c.store.DeletePod(podName)
		if c.schedulerName == "volcano" {
			c.store.DeletePodGroup("default", podName)
		}
		c.mu.Lock()
		delete(c.pods, podID)
		c.mu.Unlock()
	}

	// Step 2b: (removed — the Java broker now only re-sends unschedulable pods
	// when completions have freed capacity, so the scheduler's backoff queue is
	// naturally flushed by the completed pod DELETE events.)
	staleDeleted := 0

	// Step 2c: Drain late bindings from the previous round. These are pods the
	// scheduler bound after the round closed. Exclude them from this round's
	// pod list and include them directly in the decision.
	var lateAssignments []scheduler.PodAssignment
	if !c.testMode {
		lateAssignments = c.round.DrainLateBindings()
		if len(lateAssignments) > 0 {
			lateBound := make(map[int]bool, len(lateAssignments))
			for _, a := range lateAssignments {
				lateBound[a.PodID] = true
			}
			filtered := make([]CsPod, 0, len(snapshot.Pods))
			for _, p := range snapshot.Pods {
				if !lateBound[p.ID] {
					filtered = append(filtered, p)
				}
			}
			logJSON(map[string]interface{}{
				"action": "HandleSchedule", "roundId": rid,
				"phase": "late_bindings", "count": len(lateAssignments),
				"warning": "scheduler bound pods after previous round closed — these will be merged into this round's decision",
			})
			snapshot.Pods = filtered
		}
	}

	// Step 3: No pending pods → return empty
	if len(snapshot.Pods) == 0 {
		logJSON(map[string]interface{}{
			"action": "HandleSchedule", "roundId": rid,
			"durationMs": time.Since(t0).Milliseconds(),
			"result": "empty", "scheduled": 0, "unschedulable": 0,
		})
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(scheduler.BatchDecision{
			Scheduled: []scheduler.PodAssignment{}, Unschedulable: []scheduler.PodFailure{},
		})
		return
	}

	// Step 4: Create pods in store
	for _, csPod := range snapshot.Pods {
		pod := BuildPod(csPod, c.schedulerName)
		c.store.CreatePod(pod)
		if c.schedulerName == "volcano" {
			queue := csPod.Queue
			if queue == "" {
				queue = "default"
			}
			// Create the queue on demand if it doesn't exist yet
			c.ensureVolcanoQueue(queue)
			c.store.CreatePodGroup("default", pod.Name, buildPodGroup(pod.Name, "default", queue))
		}
		c.mu.Lock()
		podCopy := csPod
		if podCopy.Status == "" {
			podCopy.Status = "Pending"
		}
		c.pods[csPod.ID] = &podCopy
		c.mu.Unlock()
	}

	prepOverhead := time.Since(t0)
	logJSON(map[string]interface{}{
		"action":       "HandleSchedule",
		"roundId":      rid,
		"phase":        "prep_done",
		"prepMs":       prepOverhead.Milliseconds(),
		"staleDeleted": staleDeleted,
		"podsCreated":  len(snapshot.Pods),
	})

	// Step 5: Schedule
	var decision scheduler.BatchDecision

	if c.testMode {
		decision = c.scheduleTestMode(snapshot.Pods, snapshot.Nodes)
		logJSON(map[string]interface{}{
			"action":      "testModeResult",
			"roundId":     rid,
			"newPods":     len(snapshot.Pods),
			"scheduled":   len(decision.Scheduled),
			"unschedulable": len(decision.Unschedulable),
		})
		// Update pod status so future rounds know which nodes are occupied
		c.mu.Lock()
		for _, a := range decision.Scheduled {
			if pod, ok := c.pods[a.PodID]; ok {
				pod.Status = "Running"
				pod.NodeID = a.NodeID
			}
		}
		c.mu.Unlock()
	} else {
		if err := c.round.Begin(len(snapshot.Pods)); err != nil {
			logJSON(map[string]interface{}{
				"action": "HandleSchedule", "roundId": rid,
				"durationMs": time.Since(t0).Milliseconds(),
				"result": "conflict", "error": err.Error(),
			})
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}

		ctx := context.Background()
		var err error
		decision, err = c.round.Wait(ctx)
		if err != nil {
			// Timeout: Volcano may not report unschedulable pods explicitly (it just skips them).
			// Treat the partial result as a valid decision — unresolved pods become Unschedulable.
			resolvedIDs := make(map[int]bool)
			for _, a := range decision.Scheduled {
				resolvedIDs[a.PodID] = true
			}
			for _, f := range decision.Unschedulable {
				resolvedIDs[f.PodID] = true
			}
			for _, csPod := range snapshot.Pods {
				if !resolvedIDs[csPod.ID] {
					decision.Unschedulable = append(decision.Unschedulable, scheduler.PodFailure{
						PodID:  csPod.ID,
						Reason: "scheduling timeout: no decision received from scheduler",
					})
				}
			}
			logJSON(map[string]interface{}{
				"action": "HandleSchedule", "roundId": rid,
				"podCount": len(snapshot.Pods),
				"durationMs":    time.Since(t0).Milliseconds(),
				"result":        "timeout_partial",
				"scheduled":     len(decision.Scheduled),
				"unschedulable": len(decision.Unschedulable),
			})
		}
	}

	// Merge late bindings into the decision
	if len(lateAssignments) > 0 {
		decision.Scheduled = append(lateAssignments, decision.Scheduled...)
	}

	logJSON(map[string]interface{}{
		"action": "HandleSchedule", "roundId": rid,
		"podCount":     len(snapshot.Pods),
		"durationMs":   time.Since(t0).Milliseconds(),
		"result":       "ok",
		"scheduled":    len(decision.Scheduled),
		"unschedulable": len(decision.Unschedulable),
	})

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(decision)
}

func (c *Communicator) HandleSchedulePods(w http.ResponseWriter, r *http.Request) {
	t0 := time.Now()
	rid := roundID(r)

	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	var csPods []CsPod
	if err := json.NewDecoder(r.Body).Decode(&csPods); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	for _, csPod := range csPods {
		pod := BuildPod(csPod, c.schedulerName)
		c.store.CreatePod(pod)
		if c.schedulerName == "volcano" {
			queue := csPod.Queue
			if queue == "" {
				queue = "default"
			}
			c.ensureVolcanoQueue(queue)
			c.store.CreatePodGroup("default", pod.Name, buildPodGroup(pod.Name, "default", queue))
		}
		c.mu.Lock()
		podCopy := csPod
		if podCopy.Status == "" {
			podCopy.Status = "Pending"
		}
		c.pods[csPod.ID] = &podCopy
		c.mu.Unlock()
	}

	var decision scheduler.BatchDecision

	if c.testMode {
		decision = c.scheduleTestMode(csPods, c.snapshotNodesFromStore())
	} else {
		if err := c.round.Begin(len(csPods)); err != nil {
			http.Error(w, err.Error(), http.StatusConflict)
			return
		}
		ctx := context.Background()
		var err error
		decision, err = c.round.Wait(ctx)
		if err != nil {
			logJSON(map[string]interface{}{
				"action": "HandleSchedulePods", "roundId": rid,
				"podCount": len(csPods), "durationMs": time.Since(t0).Milliseconds(),
				"result": "timeout", "error": err.Error(),
			})
			http.Error(w, err.Error(), http.StatusRequestTimeout)
			return
		}
	}

	logJSON(map[string]interface{}{
		"action": "HandleSchedulePods", "roundId": rid,
		"podCount": len(csPods), "durationMs": time.Since(t0).Milliseconds(),
		"result": "ok", "scheduled": len(decision.Scheduled), "unschedulable": len(decision.Unschedulable),
	})

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(decision)
}

func (c *Communicator) HandleUpdateState(w http.ResponseWriter, r *http.Request) {
	t0 := time.Now()
	rid := roundID(r)

	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	var req struct {
		PodID int `json:"podId"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	c.mu.RLock()
	_, exists := c.pods[req.PodID]
	c.mu.RUnlock()

	if !exists {
		http.Error(w, "Pod not found", http.StatusNotFound)
		return
	}

	podName := fmt.Sprintf("cspod-%d", req.PodID)
	c.store.DeletePod(podName)
	c.mu.Lock()
	delete(c.pods, req.PodID)
	c.mu.Unlock()

	var decision scheduler.BatchDecision

	if c.testMode {
		c.mu.RLock()
		var pendingPods []scheduler.SchedulerPod
		for id, pod := range c.pods {
			if pod.NodeName == "" {
				pendingPods = append(pendingPods, scheduler.SchedulerPod{ID: id, Name: pod.Name})
			}
		}
		c.mu.RUnlock()

		csNodes := c.store.GetNodes()
		nodes := make([]scheduler.SchedulerNode, len(csNodes))
		for i, node := range csNodes {
			nodeID, err := extractNodeID(node.Name)
			if err != nil {
				nodeID = -1
			}
			nodes[i] = scheduler.SchedulerNode{ID: nodeID, Name: node.Name}
		}
		decision = c.testSched.Schedule(pendingPods, nodes)
	} else {
		decision = scheduler.BatchDecision{
			Scheduled: []scheduler.PodAssignment{}, Unschedulable: []scheduler.PodFailure{},
		}
	}

	logJSON(map[string]interface{}{
		"action": "HandleUpdateState", "roundId": rid,
		"deletedPod": req.PodID, "durationMs": time.Since(t0).Milliseconds(),
		"rescheduled": len(decision.Scheduled),
	})

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(decision)
}

func (c *Communicator) HandleReset(w http.ResponseWriter, r *http.Request) {
	rid := roundID(r)

	if r.Method != http.MethodDelete {
		http.Error(w, "Only DELETE allowed", http.StatusMethodNotAllowed)
		return
	}

	if !c.testMode && c.round != nil {
		c.round.Reset()
	}
	c.store.DeleteAll()
	c.store.Reset()
	c.mu.Lock()
	c.pods = make(map[int]*CsPod)
	c.mu.Unlock()

	// Pre-create weighted Volcano queues so the proportion plugin can arbitrate.
	// Volcano creates "root" and "default" on its own; additional queues are
	// created on demand when pods reference them (see HandleSchedule step 4).
	// No queues are pre-created here to avoid starving the default queue via
	// the proportion plugin when tests don't use multi-queue.

	logJSON(map[string]interface{}{
		"action": "HandleReset", "roundId": rid, "result": "ok",
	})

	w.WriteHeader(http.StatusOK)
	fmt.Fprintf(w, "Reset complete\n")
}

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

// buildPodGroup constructs a minimal Volcano PodGroup raw map for a single pod.
// minAvailable=1 means Volcano will schedule the pod as soon as one node fits it.
// The queue parameter determines which Volcano queue this pod belongs to.
func buildPodGroup(name, namespace, queue string) map[string]interface{} {
	if queue == "" {
		queue = "default"
	}
	return map[string]interface{}{
		"apiVersion": "scheduling.volcano.sh/v1beta1",
		"kind":       "PodGroup",
		"metadata": map[string]interface{}{
			"name":      name,
			"namespace": namespace,
		},
		"spec": map[string]interface{}{
			"minMember": 1,
			"queue":     queue,
		},
		"status": map[string]interface{}{
			"phase": "Pending",
		},
	}
}

// volcanoQueueWeights maps queue names to their proportion weights.
// Volcano creates "default" with weight 1 on its own. Additional queues
// are created on demand when pods reference them.
var volcanoQueueWeights = map[string]int{
	"high-priority": 3,
	"batch":         1,
}

// ensureVolcanoQueue creates a Volcano queue if it doesn't already exist in the store.
// The "default" queue is created by Volcano itself, so we skip it here.
func (c *Communicator) ensureVolcanoQueue(name string) {
	if name == "default" {
		return // Volcano creates this on startup
	}
	if _, exists := c.store.GetQueue(name); exists {
		return // already created
	}
	weight, ok := volcanoQueueWeights[name]
	if !ok {
		weight = 1
	}
	raw := map[string]interface{}{
		"apiVersion": "scheduling.volcano.sh/v1beta1",
		"kind":       "Queue",
		"metadata": map[string]interface{}{
			"name": name,
		},
		"spec": map[string]interface{}{
			"weight": weight,
		},
		"status": map[string]interface{}{
			"state": "Open",
		},
	}
	c.store.CreateQueue(name, raw)
	logJSON(map[string]interface{}{
		"action": "ensureVolcanoQueue",
		"queue":  name,
		"weight": weight,
	})
}

// scheduleTestMode runs the built-in round-robin scheduler.
func (c *Communicator) scheduleTestMode(csPods []CsPod, csNodes []CsNode) scheduler.BatchDecision {
	// Build a map of PEs and RAM already consumed by running pods on each node
	usedPes := make(map[int]int)
	usedRam := make(map[int]int)
	c.mu.Lock()
	for _, pod := range c.pods {
		if pod.Status == "Running" {
			usedPes[pod.NodeID] += pod.Pes
			usedRam[pod.NodeID] += pod.RamRequest
		}
	}
	c.mu.Unlock()

	nodes := make([]scheduler.SchedulerNode, len(csNodes))
	for i, node := range csNodes {
		free := node.Pes - usedPes[node.ID]
		if free < 0 {
			free = 0
		}
		freeRam := node.RAMAval - usedRam[node.ID]
		if node.RAMAval == 0 {
			freeRam = -1 // no RAM tracking on this node
		} else if freeRam < 0 {
			freeRam = 0
		}
		logJSON(map[string]interface{}{
			"action": "testModeNodeCapacity",
			"nodeID": node.ID, "totalPes": node.Pes,
			"usedPes": usedPes[node.ID], "freePes": free,
			"totalRam": node.RAMAval, "usedRam": usedRam[node.ID], "freeRam": freeRam,
		})
		nodes[i] = scheduler.SchedulerNode{ID: node.ID, Name: node.Name, Pes: free, Ram: freeRam}
	}
	pods := make([]scheduler.SchedulerPod, len(csPods))
	for i, csPod := range csPods {
		pods[i] = scheduler.SchedulerPod{ID: csPod.ID, Name: csPod.Name, Pes: csPod.Pes, Ram: csPod.RamRequest}
	}
	return c.testSched.Schedule(pods, nodes)
}

// snapshotNodesFromStore builds CsNode objects from the in-memory K8s store.
// Used by the legacy HandleSchedulePods endpoint which doesn't receive node info.
// Resource fields (Pes) are read from the K8s node's Allocatable CPU.
func (c *Communicator) snapshotNodesFromStore() []CsNode {
	k8sNodes := c.store.GetNodes()
	csNodes := make([]CsNode, 0, len(k8sNodes))
	for _, n := range k8sNodes {
		id, err := extractNodeID(n.Name)
		if err != nil {
			continue
		}
		pes := int(n.Status.Allocatable.Cpu().Value())
		csNodes = append(csNodes, CsNode{ID: id, Name: n.Name, Pes: pes})
	}
	return csNodes
}

func (c *Communicator) applyNodeDiff(incomingNodes []CsNode) error {
	currentNodes := c.store.GetNodes()
	currentMap := make(map[int]*corev1.Node)
	for _, node := range currentNodes {
		if id, err := extractNodeID(node.Name); err == nil {
			currentMap[id] = node
		}
	}

	incomingMap := make(map[int]CsNode)
	for _, node := range incomingNodes {
		incomingMap[node.ID] = node
	}

	for id, node := range currentMap {
		if _, exists := incomingMap[id]; !exists {
			c.store.DeleteNode(node.Name)
		}
	}

	for id, csNode := range incomingMap {
		if _, exists := currentMap[id]; !exists {
			node := BuildNode(csNode, c.schedulerName)
			c.store.CreateNode(node)
		}
	}

	return nil
}
