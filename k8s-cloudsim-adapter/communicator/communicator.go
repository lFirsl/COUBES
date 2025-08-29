package communicator

import (
	"encoding/json"
	"fmt"
	"k8s-cloudsim-adapter/kube_client"
	"k8s-cloudsim-adapter/utils"
	corev1 "k8s.io/api/core/v1"
	"log"
	"net/http"
	"strconv"
	"sync"
	"time"
)

type Communicator struct {
	extenderURL string
	mu          sync.RWMutex
	pods        map[int]*CsPod
	kubeClient  *kube_client.KubeClient
}

func NewCommunicator(url string, kc *kube_client.KubeClient) *Communicator {
	return &Communicator{
		extenderURL: url,
		pods:        make(map[int]*CsPod),
		kubeClient:  kc,
	}
}

// HandleNodes processes node sync requests from CloudSim
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

	// Step 1: Get current nodes from K8s cluster
	k8sNodes, err := c.kubeClient.GetNodes()
	if err != nil {
		http.Error(w, "Error fetching current cluster nodes: "+err.Error(), http.StatusInternalServerError)
		return
	}

	currentMap := make(map[int]*corev1.Node)
	for _, kNode := range k8sNodes {
		if id, err := extractNodeID(kNode.Name); err == nil {
			currentMap[id] = kNode
		}
	}

	// Step 2: Build incoming map
	incomingMap := map[int]CsNode{}
	for _, node := range incomingNodes {
		incomingMap[node.ID] = node
	}

	// Step 3: Determine deletions (nodes in cluster but missing from CloudSim)
	var toDelete []*corev1.Node
	for id, node := range currentMap {
		if _, exists := incomingMap[id]; !exists {
			toDelete = append(toDelete, node)
		}
	}

	// Step 4: Determine additions (nodes in CloudSim but missing from cluster)
	var toAdd []CsNode
	for id, newNode := range incomingMap {
		if _, exists := currentMap[id]; !exists {
			toAdd = append(toAdd, newNode)
		}
	}

	log.Printf("DEBUG: Nodes to add (%d) and delete (%d)", len(toAdd), len(toDelete))

	// Step 5: Delete nodes
	if len(toDelete) > 0 {
		if err := c.kubeClient.DeleteNodes(toDelete); err != nil {
			http.Error(w, "Failed to delete outdated nodes: "+err.Error(), http.StatusInternalServerError)
			return
		}
	}

	// Step 6: Add nodes
	if len(toAdd) > 0 {
		if err := c.SendFakeNodesFromCs(toAdd); err != nil {
			http.Error(w, "Failed to create new nodes: "+err.Error(), http.StatusInternalServerError)
			return
		}
	}

	// Step 7: Wait for readiness
	const maxAttempts = 20
	const delay = time.Second
	for i := 0; i < maxAttempts; i++ {
		ok, err := c.kubeClient.AreAllNodesReady()
		if err != nil {
			http.Error(w, "Error checking node readiness: "+err.Error(), http.StatusInternalServerError)
			return
		}
		if ok {
			w.WriteHeader(http.StatusOK)
			fmt.Fprintf(w, "Synced %d nodes (added: %d, deleted: %d)\n", len(incomingMap), len(toAdd), len(toDelete))
			return
		}
		time.Sleep(delay)
	}

	http.Error(w, "Timeout waiting for all nodes to become ready", http.StatusRequestTimeout)
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
	defer c.mu.RUnlock()
	pod, exists := c.pods[podID]
	if !exists {
		http.Error(w, "CsPod not found", http.StatusNotFound)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(pod)
}

func (c *Communicator) HandleBatchPods(w http.ResponseWriter, r *http.Request) {
	log.Printf("Starting HandleBatchPods()")

	if r.Method != http.MethodPost {
		http.Error(w, "Only POST method is allowed", http.StatusMethodNotAllowed)
		return
	}

	// Step 1: Decode input
	var newPods []CsPod
	if err := json.NewDecoder(r.Body).Decode(&newPods); err != nil {
		http.Error(w, "Invalid JSON: "+err.Error(), http.StatusBadRequest)
		return
	}

	// Step 2: Track them in communicator memory
	c.mu.Lock()
	for i := range newPods {
		if newPods[i].Status == "" {
			newPods[i].Status = "Pending"
		}
		podCopy := newPods[i]
		c.pods[podCopy.ID] = &podCopy
	}
	c.mu.Unlock()

	// Step 3: Send to Kubernetes
	if err := c.SendFakePodsFromCs(newPods); err != nil {
		http.Error(w, "Failed to send pods to Kubernetes: "+err.Error(), http.StatusInternalServerError)
		return
	}
	log.Printf("Sent %d fake pods to Kubernetes", len(newPods))

	// Step 4: Wait until they are all scheduled
	const maxAttempts = 30
	const delay = time.Second
	scheduled := false

	for i := 0; i < maxAttempts; i++ {
		ok, err := c.kubeClient.AreAllPodsScheduled("")
		if err != nil {
			http.Error(w, "Error checking pod scheduling: "+err.Error(), http.StatusInternalServerError)
			return
		}
		if ok {
			scheduled = true
			break
		}
		time.Sleep(delay)
	}

	if !scheduled {
		http.Error(w, "Timeout: Not all pods were scheduled in time", http.StatusRequestTimeout)
		return
	}

	// Step 5: Fetch from Kubernetes
	k8sPods, err := c.kubeClient.GetPods("default")
	if err != nil {
		http.Error(w, "Failed to fetch pods from Kubernetes: "+err.Error(), http.StatusInternalServerError)
		return
	}

	// Step 6: Convert to []CsPod
	csPods := ConvertToCsPods(k8sPods)

	log.Printf("Pods scheduling success - returning response")
	for _, pod := range csPods {
		log.Println("Pod", pod.ID, "assigned to node", pod.NodeID)
	}

	// Step 7: Return result
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(csPods); err != nil {
		http.Error(w, "Failed to encode response: "+err.Error(), http.StatusInternalServerError)
		return
	}
	log.Println("Response sent successfully with", len(csPods), "pods")

}

func (c *Communicator) HandleDeleteCloudletAndWait(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		http.Error(w, "Only POST allowed", http.StatusMethodNotAllowed)
		return
	}

	// Accept array of pods for consistency (even if only one)
	var csPods []CsPod
	if err := json.NewDecoder(r.Body).Decode(&csPods); err != nil {
		http.Error(w, "Invalid JSON payload: "+err.Error(), http.StatusBadRequest)
		return
	}

	if len(csPods) == 0 {
		http.Error(w, "No cloudlet provided", http.StatusBadRequest)
		return
	}

	// Take first pod
	csPod := csPods[0]

	newPods, err := c.kubeClient.DeletePodAndWaitForRescheduling(csPod.ID)
	if err != nil {
		http.Error(w, "Error during deletion and rescheduling: "+err.Error(), http.StatusInternalServerError)
		return
	}

	csPodsResult := ConvertToCsPods(newPods)
	if len(csPodsResult) != 0 {
		log.Printf("Assigning Pod %d to Node %d...", csPodsResult[0].ID, csPodsResult[0].NodeID)
	} else {
		log.Printf("No new pods to assign...")
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(csPodsResult); err != nil {
		http.Error(w, "Failed to encode response: "+err.Error(), http.StatusInternalServerError)
		return
	}
}
