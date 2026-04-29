package fakeapi

import (
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net/http"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"

	"k8s-cloudsim-adapter/store"
)

// FakeAPIHandler implements the Kubernetes API surface required by the kube-scheduler.
type FakeAPIHandler struct {
	store *store.InMemoryStore
}

// NewFakeAPIHandler creates a new FakeAPIHandler.
func NewFakeAPIHandler(s *store.InMemoryStore) *FakeAPIHandler {
	return &FakeAPIHandler{store: s}
}

// watchStream is a helper that upgrades an HTTP response to a WatchStream.
// It sets chunked transfer encoding, subscribes to the broadcast channel,
// and JSON-encodes and flushes each event immediately.
func watchStream(w http.ResponseWriter, subscribe func() <-chan metav1.WatchEvent, cancel func(<-chan metav1.WatchEvent)) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming not supported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Transfer-Encoding", "chunked")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)

	ch := subscribe()
	defer cancel(ch)

	enc := json.NewEncoder(w)
	for event := range ch {
		if err := enc.Encode(event); err != nil {
			log.Printf("error encoding watch event: %v", err)
			return
		}
		flusher.Flush()
	}
}

// HandleListNodes implements GET /api/v1/nodes
func (h *FakeAPIHandler) HandleListNodes(w http.ResponseWriter, r *http.Request) {
	// Check if this is a watch request
	if r.URL.Query().Get("watch") == "true" {
		watchStream(w, h.store.SubscribeNodes, h.store.CancelNodeSubscription)
		return
	}

	// Regular list request
	nodes := h.store.GetNodes()
	nodeList := &corev1.NodeList{
		TypeMeta: metav1.TypeMeta{
			Kind:       "NodeList",
			APIVersion: "v1",
		},
		Items: make([]corev1.Node, 0, len(nodes)),
	}

	for _, node := range nodes {
		nodeList.Items = append(nodeList.Items, *node)
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(nodeList); err != nil {
		http.Error(w, fmt.Sprintf("error encoding node list: %v", err), http.StatusInternalServerError)
		return
	}
}

// HandleListPods implements GET /api/v1/pods
func (h *FakeAPIHandler) HandleListPods(w http.ResponseWriter, r *http.Request) {
	// Check if this is a watch request
	if r.URL.Query().Get("watch") == "true" {
		watchStream(w, h.store.SubscribePods, h.store.CancelPodSubscription)
		return
	}

	// Regular list request
	pods := h.store.GetPods()
	podList := &corev1.PodList{
		TypeMeta: metav1.TypeMeta{
			Kind:       "PodList",
			APIVersion: "v1",
		},
		Items: make([]corev1.Pod, 0, len(pods)),
	}

	for _, pod := range pods {
		podList.Items = append(podList.Items, *pod)
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(podList); err != nil {
		http.Error(w, fmt.Sprintf("error encoding pod list: %v", err), http.StatusInternalServerError)
		return
	}
}

// HandleGetNode implements GET /api/v1/nodes/{name}
func (h *FakeAPIHandler) HandleGetNode(w http.ResponseWriter, r *http.Request) {
	// Extract node name from URL path
	// Expected format: /api/v1/nodes/{name}
	pathParts := splitPath(r.URL.Path)
	if len(pathParts) < 4 {
		http.Error(w, "invalid node path", http.StatusBadRequest)
		return
	}
	nodeName := pathParts[3]

	node, ok := h.store.GetNode(nodeName)
	if !ok {
		http.Error(w, fmt.Sprintf("node %s not found", nodeName), http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(node); err != nil {
		http.Error(w, fmt.Sprintf("error encoding node: %v", err), http.StatusInternalServerError)
		return
	}
}

// HandleUpdateNode implements PUT /api/v1/nodes/{name}
func (h *FakeAPIHandler) HandleUpdateNode(w http.ResponseWriter, r *http.Request) {
	// Extract node name from URL path
	pathParts := splitPath(r.URL.Path)
	if len(pathParts) < 4 {
		http.Error(w, "invalid node path", http.StatusBadRequest)
		return
	}
	nodeName := pathParts[3]

	// Decode the updated node from request body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, fmt.Sprintf("error reading request body: %v", err), http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var node corev1.Node
	if err := json.Unmarshal(body, &node); err != nil {
		http.Error(w, fmt.Sprintf("error decoding node: %v", err), http.StatusBadRequest)
		return
	}

	// Update the node in the store
	h.store.UpdateNode(nodeName, &node)

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(&node); err != nil {
		http.Error(w, fmt.Sprintf("error encoding node: %v", err), http.StatusInternalServerError)
		return
	}
}

// splitPath splits a URL path into components, skipping empty strings
func splitPath(path string) []string {
	parts := []string{}
	current := ""
	for i := 0; i < len(path); i++ {
		if path[i] == '/' {
			if current != "" {
				parts = append(parts, current)
				current = ""
			}
		} else {
			current += string(path[i])
		}
	}
	if current != "" {
		parts = append(parts, current)
	}
	return parts
}

// SchedulingRoundRecorder is an interface for recording scheduling decisions.
// This will be implemented by the scheduler.SchedulingRound type.
type SchedulingRoundRecorder interface {
	RecordBinding(podName, nodeName string)
	RecordFailure(podName, reason string)
}

// HandleBinding implements POST /api/v1/namespaces/default/pods/{name}/binding
func (h *FakeAPIHandler) HandleBinding(w http.ResponseWriter, r *http.Request, recorder SchedulingRoundRecorder) {
	// Extract pod name from URL path
	// Expected format: /api/v1/namespaces/default/pods/{name}/binding
	pathParts := splitPath(r.URL.Path)
	if len(pathParts) < 6 {
		http.Error(w, "invalid binding path", http.StatusBadRequest)
		return
	}
	podName := pathParts[5]

	// Read and decode the binding
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, fmt.Sprintf("error reading request body: %v", err), http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	// Decode the binding - support both JSON and protobuf
	var binding corev1.Binding
	contentType := r.Header.Get("Content-Type")
	
	if contentType == "application/vnd.kubernetes.protobuf" {
		// Simple protobuf parsing: extract pod name and node name from the body
		// The protobuf format contains the pod name and node name as strings
		// We can extract them by looking for "csnode-" pattern
		bodyStr := string(body)
		
		// Find node name (format: csnode-N)
		nodeNameStart := -1
		for i := 0; i < len(bodyStr)-7; i++ {
			if bodyStr[i:i+7] == "csnode-" {
				nodeNameStart = i
				break
			}
		}
		
		if nodeNameStart == -1 {
			log.Printf("could not find node name in protobuf binding")
			http.Error(w, "could not parse protobuf binding", http.StatusBadRequest)
			return
		}
		
		// Extract node name (csnode-N where N is a number)
		nodeNameEnd := nodeNameStart + 7
		for nodeNameEnd < len(bodyStr) && bodyStr[nodeNameEnd] >= '0' && bodyStr[nodeNameEnd] <= '9' {
			nodeNameEnd++
		}
		nodeName := bodyStr[nodeNameStart:nodeNameEnd]
		
		// Create a minimal binding object
		binding = corev1.Binding{
			Target: corev1.ObjectReference{
				Name: nodeName,
			},
		}
		
		log.Printf("Parsed protobuf binding: pod=%s -> node=%s", podName, nodeName)
	} else {
		// Try JSON decoding
		if err := json.Unmarshal(body, &binding); err != nil {
			log.Printf("error decoding JSON binding: %v", err)
			http.Error(w, fmt.Sprintf("error decoding JSON binding: %v", err), http.StatusBadRequest)
			return
		}
	}

	// Extract target node name
	nodeName := binding.Target.Name
	if nodeName == "" {
		http.Error(w, "binding target node name is empty", http.StatusBadRequest)
		return
	}

	// Update the pod in the store to set NodeName
	pod, ok := h.store.GetPod(podName)
	if !ok {
		http.Error(w, fmt.Sprintf("pod %s not found", podName), http.StatusNotFound)
		return
	}

	pod.Spec.NodeName = nodeName
	pod.Status.Phase = corev1.PodRunning // tell Volcano this pod is running so it counts against node resources
	h.store.UpdatePod(podName, pod)

	// Record the binding decision
	if recorder != nil {
		recorder.RecordBinding(podName, nodeName)
	}

	w.WriteHeader(http.StatusCreated)
}

// HandlePodStatusPatch implements PATCH /api/v1/namespaces/default/pods/{name}/status
func (h *FakeAPIHandler) HandlePodStatusPatch(w http.ResponseWriter, r *http.Request, recorder SchedulingRoundRecorder) {
	// Extract pod name from URL path
	// Expected format: /api/v1/namespaces/default/pods/{name}/status
	pathParts := splitPath(r.URL.Path)
	if len(pathParts) < 6 {
		http.Error(w, "invalid status patch path", http.StatusBadRequest)
		return
	}
	podName := pathParts[5]

	// Read the patch body
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, fmt.Sprintf("error reading request body: %v", err), http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	// Parse the status update to check for Unschedulable condition
	var statusUpdate corev1.Pod
	if err := json.Unmarshal(body, &statusUpdate); err != nil {
		http.Error(w, fmt.Sprintf("error decoding status update: %v", err), http.StatusBadRequest)
		return
	}

	// Check for Unschedulable condition
	var unschedulableReason string
	for _, condition := range statusUpdate.Status.Conditions {
		if condition.Type == corev1.PodScheduled && condition.Status == corev1.ConditionFalse && condition.Reason == corev1.PodReasonUnschedulable {
			unschedulableReason = condition.Message
			break
		}
	}

	if unschedulableReason != "" {
		// Record the failure
		if recorder != nil {
			recorder.RecordFailure(podName, unschedulableReason)
		}
	}

	// Update the pod status in the store
	pod, ok := h.store.GetPod(podName)
	if !ok {
		http.Error(w, fmt.Sprintf("pod %s not found", podName), http.StatusNotFound)
		return
	}

	pod.Status = statusUpdate.Status
	h.store.UpdatePod(podName, pod)

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(pod); err != nil {
		http.Error(w, fmt.Sprintf("error encoding pod: %v", err), http.StatusInternalServerError)
		return
	}
}

// HandleListNamespaces implements GET /api/v1/namespaces
func (h *FakeAPIHandler) HandleListNamespaces(w http.ResponseWriter, r *http.Request) {
	// Check if this is a watch request
	if r.URL.Query().Get("watch") == "true" {
		// Return an idle watch stream (no events)
		idleWatchStream(w)
		return
	}

	// Return a namespace list containing only "default"
	namespaceList := &corev1.NamespaceList{
		TypeMeta: metav1.TypeMeta{
			Kind:       "NamespaceList",
			APIVersion: "v1",
		},
		Items: []corev1.Namespace{
			{
				ObjectMeta: metav1.ObjectMeta{
					Name: "default",
				},
				Status: corev1.NamespaceStatus{
					Phase: corev1.NamespaceActive,
				},
			},
		},
	}

	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(namespaceList); err != nil {
		http.Error(w, fmt.Sprintf("error encoding namespace list: %v", err), http.StatusInternalServerError)
		return
	}
}

// idleWatchStream returns a watch stream that never sends events.
// Used for stub endpoints that support watch but have no data.
func idleWatchStream(w http.ResponseWriter) {
	flusher, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming not supported", http.StatusInternalServerError)
		return
	}

	w.Header().Set("Transfer-Encoding", "chunked")
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	flusher.Flush()

	// Keep the connection open but send no events
	// The client will eventually timeout or close the connection
	select {}
}

// Stub endpoint handlers - return valid empty lists with watch support

// HandleListServices implements GET /api/v1/services
func (h *FakeAPIHandler) HandleListServices(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	serviceList := &corev1.ServiceList{
		TypeMeta: metav1.TypeMeta{
			Kind:       "ServiceList",
			APIVersion: "v1",
		},
		Items: []corev1.Service{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(serviceList)
}

// HandleListPersistentVolumes implements GET /api/v1/persistentvolumes
func (h *FakeAPIHandler) HandleListPersistentVolumes(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	pvList := &corev1.PersistentVolumeList{
		TypeMeta: metav1.TypeMeta{
			Kind:       "PersistentVolumeList",
			APIVersion: "v1",
		},
		Items: []corev1.PersistentVolume{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(pvList)
}

// HandleListReplicaSets implements GET /apis/apps/v1/replicasets
func (h *FakeAPIHandler) HandleListReplicaSets(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	// Return empty list with proper TypeMeta
	emptyList := map[string]interface{}{
		"kind":       "ReplicaSetList",
		"apiVersion": "apps/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListStatefulSets implements GET /apis/apps/v1/statefulsets
func (h *FakeAPIHandler) HandleListStatefulSets(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "StatefulSetList",
		"apiVersion": "apps/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListDaemonSets implements GET /apis/apps/v1/daemonsets
func (h *FakeAPIHandler) HandleListDaemonSets(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "DaemonSetList",
		"apiVersion": "apps/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListStorageClasses implements GET /apis/storage.k8s.io/v1/storageclasses
func (h *FakeAPIHandler) HandleListStorageClasses(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "StorageClassList",
		"apiVersion": "storage.k8s.io/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListCSIDrivers implements GET /apis/storage.k8s.io/v1/csidrivers
func (h *FakeAPIHandler) HandleListCSIDrivers(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "CSIDriverList",
		"apiVersion": "storage.k8s.io/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListCSINodes implements GET /apis/storage.k8s.io/v1/csinodes
func (h *FakeAPIHandler) HandleListCSINodes(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "CSINodeList",
		"apiVersion": "storage.k8s.io/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListPodDisruptionBudgets implements GET /apis/policy/v1/poddisruptionbudgets
func (h *FakeAPIHandler) HandleListPodDisruptionBudgets(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "PodDisruptionBudgetList",
		"apiVersion": "policy/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListJobs implements GET /apis/batch/v1/jobs
func (h *FakeAPIHandler) HandleListJobs(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "JobList",
		"apiVersion": "batch/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}
// HandleListReplicationControllers implements GET /api/v1/replicationcontrollers
func (h *FakeAPIHandler) HandleListReplicationControllers(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "ReplicationControllerList",
		"apiVersion": "v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListCSIStorageCapacities implements GET /apis/storage.k8s.io/v1/csistoragecapacities
func (h *FakeAPIHandler) HandleListCSIStorageCapacities(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "CSIStorageCapacityList",
		"apiVersion": "storage.k8s.io/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}

// HandleListVolumeAttachments implements GET /apis/storage.k8s.io/v1/volumeattachments
func (h *FakeAPIHandler) HandleListVolumeAttachments(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "VolumeAttachmentList",
		"apiVersion": "storage.k8s.io/v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}
// HandleListPersistentVolumeClaims implements GET /api/v1/persistentvolumeclaims
func (h *FakeAPIHandler) HandleListPersistentVolumeClaims(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}

	emptyList := map[string]interface{}{
		"kind":       "PersistentVolumeClaimList",
		"apiVersion": "v1",
		"items":      []interface{}{},
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(emptyList)
}