package kube_client

import (
	"encoding/json"
	"fmt"
	"net/http"
)

func MakeCreatePodHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// Get pod name from query param
		podName := r.URL.Query().Get("name")
		if podName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		err := kc.CreateFakePod(podName)
		if err != nil {
			http.Error(w, "Failed to create pod: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "created",
			"pod":    podName,
		})
	}
}

func MakeDeletePodHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		podName := r.URL.Query().Get("name")
		if podName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		err := kc.DeletePod(podName)
		if err != nil {
			http.Error(w, "Failed to delete pod: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "deleted",
			"pod":    podName,
		})
	}
}

func MakeCreateNodeHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		nodeName := r.URL.Query().Get("name")
		if nodeName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		err := kc.CreateFakeNode(nodeName)
		if err != nil {
			http.Error(w, "Failed to create node: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "created",
			"node":   nodeName,
		})
	}
}

func MakeDeleteNodeHandler(kc *KubeClient) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		nodeName := r.URL.Query().Get("name")
		if nodeName == "" {
			http.Error(w, "Missing 'name' query parameter", http.StatusBadRequest)
			return
		}

		err := kc.DeleteNode(nodeName)
		if err != nil {
			http.Error(w, "Failed to delete node: "+err.Error(), http.StatusInternalServerError)
			return
		}

		json.NewEncoder(w).Encode(map[string]string{
			"status": "deleted",
			"node":   nodeName,
		})
	}
}

func (kc *KubeClient) HandleDeleteAllPods(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "Only DELETE allowed", http.StatusMethodNotAllowed)
		return
	}

	if err := kc.DeleteAllPods(); err != nil {
		http.Error(w, "Failed to delete pods: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte("All pods deleted"))
}

func (kc *KubeClient) HandleDeleteAllNodes(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "Only DELETE allowed", http.StatusMethodNotAllowed)
		return
	}

	if err := kc.DeleteAllNodes(); err != nil {
		http.Error(w, "Failed to delete nodes: "+err.Error(), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	w.Write([]byte("All nodes deleted"))
}

func (kc *KubeClient) HandleResetCluster(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodDelete {
		http.Error(w, "Only DELETE method is allowed", http.StatusMethodNotAllowed)
		return
	}

	if err := kc.ResetCluster(); err != nil {
		http.Error(w, fmt.Sprintf("Failed to reset cluster: %v", err), http.StatusInternalServerError)
		return
	}

	w.WriteHeader(http.StatusOK)
	fmt.Fprintln(w, "Cluster reset successfully.")
}
