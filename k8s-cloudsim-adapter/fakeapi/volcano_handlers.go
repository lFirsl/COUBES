package fakeapi

// volcano_handlers.go — Fake API server handlers for Volcano-specific CRD endpoints.
//
// WHY THIS FILE EXISTS
// ====================
// Volcano's scheduler (vc-scheduler) uses two clients when it starts:
//   1. A standard Kubernetes client (kubeClient) — for pods, nodes, events, etc.
//   2. A Volcano CRD client (vcClient) — for Queue, PodGroup, HyperNode, etc.
//
// Both clients perform API discovery before making resource requests. They call:
//   GET /api          → lists core API versions
//   GET /apis         → lists all API groups
//   GET /apis/<group>/<version>  → lists resources in that group/version
//
// If any of these return errors, the client panics or retries indefinitely.
//
// After discovery, Volcano's cache startup (newSchedulerCache) immediately calls:
//   GET  /apis/scheduling.volcano.sh/v1beta1/queues/default  → expects 404 (not found)
//   POST /apis/scheduling.volcano.sh/v1beta1/queues          → creates "root" queue
//   POST /apis/scheduling.volcano.sh/v1beta1/queues          → creates "default" queue
//
// If these fail, Volcano retries 60 times (1s apart) and never starts scheduling.
//
// WHAT WE IMPLEMENT
// =================
// 1. API discovery endpoints (minimal but valid responses)
// 2. Queue CRUD + watch (backed by the in-memory queue store)
// 3. PodGroup list + watch (empty — Volcano auto-creates per-pod PodGroups)
// 4. HyperNode, NumaTopology, NodeShard stubs (empty lists, idle watches)
// 5. PriorityClass and ResourceQuota stubs (also watched by Volcano's cache)
//
// WHAT WE DO NOT IMPLEMENT
// ========================
// PodGroup creation/update — Volcano auto-creates these internally and writes them
// back to the API server. For COUBES benchmarking we only need the list/watch to
// return empty so Volcano doesn't crash. The POST/PUT for PodGroups is accepted
// with 201/200 and discarded (we don't need to track them for scheduling decisions
// because Volcano's binding still goes through the standard /binding endpoint).

import (
	"encoding/json"
	"io"
	"log"
	"net/http"

	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
)

// ── API Discovery ─────────────────────────────────────────────────────────────

// HandleAPIVersions implements GET /api
// Returns the core API versions. The Kubernetes client calls this first.
func (h *FakeAPIHandler) HandleAPIVersions(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":       "APIVersions",
		"apiVersion": "v1",
		"versions":   []string{"v1"},
		"serverAddressByClientCIDRs": []map[string]string{
			{"clientCIDR": "0.0.0.0/0", "serverAddress": "localhost:8080"},
		},
	})
}

// HandleAPIGroups implements GET /apis
// Returns all API groups. Volcano's vcClient checks this to find scheduling.volcano.sh.
func (h *FakeAPIHandler) HandleAPIGroups(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":       "APIGroupList",
		"apiVersion": "v1",
		"groups": []map[string]interface{}{
			{
				"name": "apps",
				"versions": []map[string]string{
					{"groupVersion": "apps/v1", "version": "v1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "apps/v1", "version": "v1"},
			},
			{
				"name": "storage.k8s.io",
				"versions": []map[string]string{
					{"groupVersion": "storage.k8s.io/v1", "version": "v1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "storage.k8s.io/v1", "version": "v1"},
			},
			{
				"name": "policy",
				"versions": []map[string]string{
					{"groupVersion": "policy/v1", "version": "v1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "policy/v1", "version": "v1"},
			},
			{
				"name": "scheduling.k8s.io",
				"versions": []map[string]string{
					{"groupVersion": "scheduling.k8s.io/v1", "version": "v1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "scheduling.k8s.io/v1", "version": "v1"},
			},
			{
				"name": "batch",
				"versions": []map[string]string{
					{"groupVersion": "batch/v1", "version": "v1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "batch/v1", "version": "v1"},
			},
			// Volcano CRD groups
			{
				"name": "scheduling.volcano.sh",
				"versions": []map[string]string{
					{"groupVersion": "scheduling.volcano.sh/v1beta1", "version": "v1beta1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "scheduling.volcano.sh/v1beta1", "version": "v1beta1"},
			},
			{
				"name": "topology.volcano.sh",
				"versions": []map[string]string{
					{"groupVersion": "topology.volcano.sh/v1alpha1", "version": "v1alpha1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "topology.volcano.sh/v1alpha1", "version": "v1alpha1"},
			},
			{
				"name": "nodeinfo.volcano.sh",
				"versions": []map[string]string{
					{"groupVersion": "nodeinfo.volcano.sh/v1alpha1", "version": "v1alpha1"},
				},
				"preferredVersion": map[string]string{"groupVersion": "nodeinfo.volcano.sh/v1alpha1", "version": "v1alpha1"},
			},
		},
	})
}

// HandleAPIGroupDiscovery implements GET /apis/{group}/{version}
// Returns the resource list for a given group/version. Used by both kubeClient and vcClient.
// We serve a single handler and switch on the path.
func (h *FakeAPIHandler) HandleSchedulingVolcanoResources(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":         "APIResourceList",
		"apiVersion":   "v1",
		"groupVersion": "scheduling.volcano.sh/v1beta1",
		"resources": []map[string]interface{}{
			{
				"name":         "queues",
				"singularName": "queue",
				"namespaced":   false,
				"kind":         "Queue",
				"verbs":        []string{"get", "list", "watch", "create", "update", "patch", "delete"},
			},
			{
				"name":         "queues/status",
				"singularName": "",
				"namespaced":   false,
				"kind":         "Queue",
				"verbs":        []string{"get", "update", "patch"},
			},
			{
				"name":         "podgroups",
				"singularName": "podgroup",
				"namespaced":   true,
				"kind":         "PodGroup",
				"verbs":        []string{"get", "list", "watch", "create", "update", "patch", "delete"},
			},
		},
	})
}

func (h *FakeAPIHandler) HandleTopologyVolcanoResources(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":         "APIResourceList",
		"apiVersion":   "v1",
		"groupVersion": "topology.volcano.sh/v1alpha1",
		"resources": []map[string]interface{}{
			{
				"name":         "hypernodes",
				"singularName": "hypernode",
				"namespaced":   false,
				"kind":         "HyperNode",
				"verbs":        []string{"get", "list", "watch"},
			},
		},
	})
}

func (h *FakeAPIHandler) HandleNodeinfoVolcanoResources(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":         "APIResourceList",
		"apiVersion":   "v1",
		"groupVersion": "nodeinfo.volcano.sh/v1alpha1",
		"resources": []map[string]interface{}{
			{
				"name":         "numatopologies",
				"singularName": "numatopology",
				"namespaced":   false,
				"kind":         "Numatopology",
				"verbs":        []string{"get", "list", "watch"},
			},
		},
	})
}

// HandleCoreV1Resources implements GET /api/v1 — resource list for core v1.
func (h *FakeAPIHandler) HandleCoreV1Resources(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":         "APIResourceList",
		"apiVersion":   "v1",
		"groupVersion": "v1",
		"resources": []map[string]interface{}{
			{"name": "nodes", "singularName": "node", "namespaced": false, "kind": "Node", "verbs": []string{"get", "list", "watch", "update"}},
			{"name": "pods", "singularName": "pod", "namespaced": true, "kind": "Pod", "verbs": []string{"get", "list", "watch", "update"}},
			{"name": "namespaces", "singularName": "namespace", "namespaced": false, "kind": "Namespace", "verbs": []string{"get", "list", "watch"}},
			{"name": "services", "singularName": "service", "namespaced": true, "kind": "Service", "verbs": []string{"get", "list", "watch"}},
			{"name": "persistentvolumes", "singularName": "persistentvolume", "namespaced": false, "kind": "PersistentVolume", "verbs": []string{"get", "list", "watch"}},
			{"name": "persistentvolumeclaims", "singularName": "persistentvolumeclaim", "namespaced": true, "kind": "PersistentVolumeClaim", "verbs": []string{"get", "list", "watch"}},
			{"name": "replicationcontrollers", "singularName": "replicationcontroller", "namespaced": true, "kind": "ReplicationController", "verbs": []string{"get", "list", "watch"}},
			{"name": "resourcequotas", "singularName": "resourcequota", "namespaced": true, "kind": "ResourceQuota", "verbs": []string{"get", "list", "watch"}},
			{"name": "events", "singularName": "event", "namespaced": true, "kind": "Event", "verbs": []string{"get", "list", "watch", "create", "update", "patch"}},
		},
	})
}

// ── Volcano Queue handlers ────────────────────────────────────────────────────

// HandleListQueues implements GET /apis/scheduling.volcano.sh/v1beta1/queues
// Supports both list and watch. On watch, streams ADDED events for existing queues
// then blocks until the client disconnects.
func (h *FakeAPIHandler) HandleListQueues(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		// Send ADDED events for any queues that already exist, then stream future events.
		// This is important: Volcano's informer re-lists on reconnect and expects to see
		// existing queues as ADDED events at the start of the watch stream.
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "streaming not supported", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Transfer-Encoding", "chunked")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)

		enc := json.NewEncoder(w)
		// Replay existing queues as ADDED events
		for _, q := range h.store.GetQueues() {
			raw, _ := json.Marshal(q)
			event := metav1.WatchEvent{Type: "ADDED", Object: runtime.RawExtension{Raw: raw}}
			if err := enc.Encode(event); err != nil {
				return
			}
			flusher.Flush()
		}
		// Then stream future events
		ch := h.store.SubscribeQueues()
		defer h.store.CancelQueueSubscription(ch)
		for event := range ch {
			if err := enc.Encode(event); err != nil {
				log.Printf("queue watch encode error: %v", err)
				return
			}
			flusher.Flush()
		}
		return
	}

	queues := h.store.GetQueues()
	items := make([]interface{}, len(queues))
	for i, q := range queues {
		items[i] = q
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"apiVersion": "scheduling.volcano.sh/v1beta1",
		"kind":       "QueueList",
		"metadata":   map[string]string{"resourceVersion": "1"},
		"items":      items,
	})
}

// HandleGetQueue implements GET /apis/scheduling.volcano.sh/v1beta1/queues/{name}
// Returns 404 when the queue doesn't exist — this is what triggers Volcano to create it.
func (h *FakeAPIHandler) HandleGetQueue(w http.ResponseWriter, r *http.Request) {
	parts := splitPath(r.URL.Path)
	// path: apis / scheduling.volcano.sh / v1beta1 / queues / {name}
	if len(parts) < 5 {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}
	name := parts[4]
	q, ok := h.store.GetQueue(name)
	if !ok {
		// Return a proper Kubernetes 404 Status object so the client recognises it as NotFound
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"apiVersion": "v1",
			"kind":       "Status",
			"status":     "Failure",
			"message":    "queues.scheduling.volcano.sh \"" + name + "\" not found",
			"reason":     "NotFound",
			"code":       404,
		})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(q)
}

// HandleCreateQueue implements POST /apis/scheduling.volcano.sh/v1beta1/queues
// Volcano calls this to create the "root" and "default" queues on startup.
func (h *FakeAPIHandler) HandleCreateQueue(w http.ResponseWriter, r *http.Request) {
	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "cannot read body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var raw map[string]interface{}
	if err := json.Unmarshal(body, &raw); err != nil {
		http.Error(w, "cannot decode queue", http.StatusBadRequest)
		return
	}

	meta, _ := raw["metadata"].(map[string]interface{})
	if meta == nil {
		meta = map[string]interface{}{}
		raw["metadata"] = meta
	}
	name, _ := meta["name"].(string)
	if name == "" {
		http.Error(w, "queue name is required", http.StatusBadRequest)
		return
	}

	// If it already exists return 409 Conflict (Volcano handles AlreadyExists gracefully)
	if _, exists := h.store.GetQueue(name); exists {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		json.NewEncoder(w).Encode(map[string]interface{}{
			"apiVersion": "v1",
			"kind":       "Status",
			"status":     "Failure",
			"reason":     "AlreadyExists",
			"code":       409,
		})
		return
	}

	// Ensure status.state is set to "Open" so Volcano considers the queue usable
	if raw["status"] == nil {
		raw["status"] = map[string]interface{}{"state": "Open"}
	}

	h.store.CreateQueue(name, raw)
	log.Printf("Volcano queue created: %s", name)

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(raw)
}

// HandleUpdateQueueStatus implements PUT /apis/scheduling.volcano.sh/v1beta1/queues/{name}/status
// Volcano updates queue status after each scheduling cycle.
func (h *FakeAPIHandler) HandleUpdateQueueStatus(w http.ResponseWriter, r *http.Request) {
	parts := splitPath(r.URL.Path)
	// path: apis / scheduling.volcano.sh / v1beta1 / queues / {name} / status
	if len(parts) < 6 {
		http.Error(w, "invalid path", http.StatusBadRequest)
		return
	}
	name := parts[4]

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "cannot read body", http.StatusBadRequest)
		return
	}
	defer r.Body.Close()

	var raw map[string]interface{}
	if err := json.Unmarshal(body, &raw); err != nil {
		http.Error(w, "cannot decode body", http.StatusBadRequest)
		return
	}

	status, _ := raw["status"].(map[string]interface{})
	updated, ok := h.store.UpdateQueueStatus(name, status)
	if !ok {
		http.Error(w, "queue not found", http.StatusNotFound)
		return
	}

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(updated)
}

// ── Volcano PodGroup handlers ─────────────────────────────────────────────────
//
// Volcano groups pods into PodGroups. For COUBES's individually-submitted pods,
// Volcano auto-creates a PodGroup per pod (minAvailable=1). We need to:
//   - Return an empty list on GET (no pre-existing PodGroups)
//   - Accept POST/PUT without error (Volcano writes them back after creation)
//   - Support watch (Volcano's informer watches for PodGroup changes)
//
// We do NOT need to store PodGroups — the scheduling decision still comes back
// through the standard /binding endpoint which the existing handler already handles.

// HandleListPodGroups implements GET /apis/scheduling.volcano.sh/v1beta1/podgroups
// and GET /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups
func (h *FakeAPIHandler) HandleListPodGroups(w http.ResponseWriter, r *http.Request) {
	// Extract namespace from path if present
	namespace := ""
	parts := splitPath(r.URL.Path)
	// path: apis / scheduling.volcano.sh / v1beta1 / namespaces / {ns} / podgroups
	if len(parts) >= 6 && parts[3] == "namespaces" {
		namespace = parts[4]
	}

	if r.URL.Query().Get("watch") == "true" {
		// Replay existing PodGroups as ADDED events, then stream future events
		flusher, ok := w.(http.Flusher)
		if !ok {
			http.Error(w, "streaming not supported", http.StatusInternalServerError)
			return
		}
		w.Header().Set("Transfer-Encoding", "chunked")
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusOK)

		enc := json.NewEncoder(w)
		for _, pg := range h.store.GetPodGroups(namespace) {
			raw, _ := json.Marshal(pg)
			event := metav1.WatchEvent{Type: "ADDED", Object: runtime.RawExtension{Raw: raw}}
			if err := enc.Encode(event); err != nil {
				return
			}
			flusher.Flush()
		}
		ch := h.store.SubscribePodGroups()
		defer h.store.CancelPodGroupSubscription(ch)
		for event := range ch {
			if err := enc.Encode(event); err != nil {
				log.Printf("podgroup watch encode error: %v", err)
				return
			}
			flusher.Flush()
		}
		return
	}

	podGroups := h.store.GetPodGroups(namespace)
	items := make([]interface{}, len(podGroups))
	for i, pg := range podGroups {
		items[i] = pg
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"apiVersion": "scheduling.volcano.sh/v1beta1",
		"kind":       "PodGroupList",
		"metadata":   map[string]string{"resourceVersion": "1"},
		"items":      items,
	})
}

// HandleCreatePodGroup implements POST /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups
// Accept and discard — we don't need to track PodGroups for COUBES scheduling.
func (h *FakeAPIHandler) HandleCreatePodGroup(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	defer r.Body.Close()
	var raw map[string]interface{}
	json.Unmarshal(body, &raw) //nolint:errcheck
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusCreated)
	json.NewEncoder(w).Encode(raw)
}

// HandleUpdatePodGroup implements PUT /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups/{name}
// Volcano calls this to persist phase changes (Pending→Inqueue→Running). We store the update
// and broadcast a MODIFIED watch event so Volcano's informer picks up the new phase.
func (h *FakeAPIHandler) HandleUpdatePodGroup(w http.ResponseWriter, r *http.Request) {
	parts := splitPath(r.URL.Path)
	// path: apis / scheduling.volcano.sh / v1beta1 / namespaces / {ns} / podgroups / {name}
	namespace := "default"
	if len(parts) >= 5 && parts[3] == "namespaces" {
		namespace = parts[4]
	}

	body, _ := io.ReadAll(r.Body)
	defer r.Body.Close()
	var raw map[string]interface{}
	if err := json.Unmarshal(body, &raw); err != nil {
		http.Error(w, "cannot decode body", http.StatusBadRequest)
		return
	}

	meta, _ := raw["metadata"].(map[string]interface{})
	if meta == nil {
		meta = map[string]interface{}{}
		raw["metadata"] = meta
	}
	name, _ := meta["name"].(string)
	if name == "" && len(parts) >= 7 {
		name = parts[6]
		meta["name"] = name
	}

	// Store the updated PodGroup and broadcast MODIFIED so Volcano's informer sees the phase change
	h.store.UpdatePodGroupRaw(namespace, name, raw)

	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(raw)
}

// HandlePatchPodGroup implements PATCH /apis/scheduling.volcano.sh/v1beta1/namespaces/{ns}/podgroups/{name}
// Accept and discard.
func (h *FakeAPIHandler) HandlePatchPodGroup(w http.ResponseWriter, r *http.Request) {
	body, _ := io.ReadAll(r.Body)
	defer r.Body.Close()
	var raw map[string]interface{}
	json.Unmarshal(body, &raw) //nolint:errcheck
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(raw)
}

// ── Volcano topology / nodeinfo stubs ─────────────────────────────────────────
//
// Volcano's cache always registers informers for HyperNode (topology.volcano.sh)
// and optionally NumaTopology (nodeinfo.volcano.sh). These must return valid empty
// lists; otherwise the informer factory panics on startup.

// HandleListHyperNodes implements GET /apis/topology.volcano.sh/v1alpha1/hypernodes
func (h *FakeAPIHandler) HandleListHyperNodes(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"apiVersion": "topology.volcano.sh/v1alpha1",
		"kind":       "HyperNodeList",
		"metadata":   map[string]string{"resourceVersion": "1"},
		"items":      []interface{}{},
	})
}

// HandleListNumaTopologies implements GET /apis/nodeinfo.volcano.sh/v1alpha1/numatopologies
func (h *FakeAPIHandler) HandleListNumaTopologies(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"apiVersion": "nodeinfo.volcano.sh/v1alpha1",
		"kind":       "NumatopologyList",
		"metadata":   map[string]string{"resourceVersion": "1"},
		"items":      []interface{}{},
	})
}

// ── Kubernetes stubs needed by Volcano but not kube-scheduler ─────────────────

// HandleListPriorityClasses implements GET /apis/scheduling.k8s.io/v1/priorityclasses
// Volcano watches PriorityClasses when the PriorityClass feature gate is enabled (default: true).
func (h *FakeAPIHandler) HandleListPriorityClasses(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":       "PriorityClassList",
		"apiVersion": "scheduling.k8s.io/v1",
		"items":      []interface{}{},
	})
}

// HandleListResourceQuotas implements GET /api/v1/resourcequotas
// Volcano watches ResourceQuotas for the resourcequota plugin.
func (h *FakeAPIHandler) HandleListResourceQuotas(w http.ResponseWriter, r *http.Request) {
	if r.URL.Query().Get("watch") == "true" {
		idleWatchStream(w)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]interface{}{
		"kind":       "ResourceQuotaList",
		"apiVersion": "v1",
		"items":      []interface{}{},
	})
}

// HandleCreateEvent implements POST /api/v1/namespaces/{ns}/events
// Volcano records scheduling events. We accept and discard them.
func (h *FakeAPIHandler) HandleCreateEvent(w http.ResponseWriter, r *http.Request) {
	io.ReadAll(r.Body) //nolint:errcheck
	r.Body.Close()
	w.WriteHeader(http.StatusCreated)
}

// HandlePatchEvent implements PATCH /api/v1/namespaces/{ns}/events/{name}
func (h *FakeAPIHandler) HandlePatchEvent(w http.ResponseWriter, r *http.Request) {
	io.ReadAll(r.Body) //nolint:errcheck
	r.Body.Close()
	w.WriteHeader(http.StatusOK)
}


