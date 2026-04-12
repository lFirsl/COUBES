package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"time"

	"github.com/gorilla/mux"
	"k8s-cloudsim-adapter/communicator"
	"k8s-cloudsim-adapter/fakeapi"
	"k8s-cloudsim-adapter/scheduler"
	"k8s-cloudsim-adapter/store"
)

func main() {
	// Config
	port := ":8080"

	log.Printf("Starting K8s Sim Control Plane with Fake API Server on port %s\n", port)

	// Parse command line flags
	var schedulerName = flag.String("scheduler", "default-scheduler", "Name of the Kubernetes scheduler to use")
	var kubeconfig = flag.String("kubeconfig", "", "(ignored) absolute path to the kubeconfig file")
	var testMode = flag.Bool("test-mode", false, "Run in standalone test mode (no kube-scheduler required)")
	flag.Parse()

	// Log test mode status
	if *testMode {
		log.Printf("[TEST MODE] Adapter running in standalone test mode. No kube-scheduler connection will be made.")
		if *kubeconfig != "" {
			log.Printf("Note: --kubeconfig flag is ignored in test mode")
		}
	} else {
		// Ignore kubeconfig flag per Requirement 1.6
		if *kubeconfig != "" {
			log.Printf("Note: --kubeconfig flag is ignored in fake API server mode")
		}
	}

	fmt.Printf("Using scheduler: %s\n", *schedulerName)

	// Initialize core components
	store := store.NewInMemoryStore()
	round := scheduler.NewSchedulingRound(60 * time.Second)
	comm := communicator.NewCommunicator(store, round, *schedulerName, *testMode)
	fakeAPI := fakeapi.NewFakeAPIHandler(store)

	// Set up router
	router := mux.NewRouter()

	// --- Simulation-facing routes ---
	router.HandleFunc("/schedule", comm.HandleSchedule).Methods("POST")
	router.HandleFunc("/nodes", comm.HandleNodes).Methods("POST")
	router.HandleFunc("/schedule-pods", comm.HandleSchedulePods).Methods("POST")
	router.HandleFunc("/pods/update-state", comm.HandleUpdateState).Methods("POST")
	router.HandleFunc("/reset", comm.HandleReset).Methods("DELETE")
	router.HandleFunc("/pods/{id}/status", comm.HandlePodStatus).Methods("GET")

	// --- Kubernetes API routes (for kube-scheduler) ---
	// Only register fake Kubernetes API routes when NOT in test mode
	if !*testMode {
		// Core v1 API
		router.HandleFunc("/api/v1/nodes", fakeAPI.HandleListNodes).Methods("GET")
		router.HandleFunc("/api/v1/nodes/{name}", fakeAPI.HandleGetNode).Methods("GET")
		router.HandleFunc("/api/v1/nodes/{name}", fakeAPI.HandleUpdateNode).Methods("PUT")
		router.HandleFunc("/api/v1/pods", fakeAPI.HandleListPods).Methods("GET")
		router.HandleFunc("/api/v1/namespaces/default/pods/{name}/binding", func(w http.ResponseWriter, r *http.Request) {
			fakeAPI.HandleBinding(w, r, round)
		}).Methods("POST")
		router.HandleFunc("/api/v1/namespaces/default/pods/{name}/status", func(w http.ResponseWriter, r *http.Request) {
			fakeAPI.HandlePodStatusPatch(w, r, round)
		}).Methods("PATCH")
		router.HandleFunc("/api/v1/namespaces", fakeAPI.HandleListNamespaces).Methods("GET")

		// Stub endpoints for resources queried by kube-scheduler
		router.HandleFunc("/api/v1/services", fakeAPI.HandleListServices).Methods("GET")
		router.HandleFunc("/api/v1/persistentvolumes", fakeAPI.HandleListPersistentVolumes).Methods("GET")
		router.HandleFunc("/api/v1/persistentvolumeclaims", fakeAPI.HandleListPersistentVolumeClaims).Methods("GET")
		router.HandleFunc("/api/v1/replicationcontrollers", fakeAPI.HandleListReplicationControllers).Methods("GET")
		
		// Apps API
		router.HandleFunc("/apis/apps/v1/replicasets", fakeAPI.HandleListReplicaSets).Methods("GET")
		router.HandleFunc("/apis/apps/v1/statefulsets", fakeAPI.HandleListStatefulSets).Methods("GET")
		router.HandleFunc("/apis/apps/v1/daemonsets", fakeAPI.HandleListDaemonSets).Methods("GET")
		
		// Storage API
		router.HandleFunc("/apis/storage.k8s.io/v1/storageclasses", fakeAPI.HandleListStorageClasses).Methods("GET")
		router.HandleFunc("/apis/storage.k8s.io/v1/csidrivers", fakeAPI.HandleListCSIDrivers).Methods("GET")
		router.HandleFunc("/apis/storage.k8s.io/v1/csinodes", fakeAPI.HandleListCSINodes).Methods("GET")
		router.HandleFunc("/apis/storage.k8s.io/v1/csistoragecapacities", fakeAPI.HandleListCSIStorageCapacities).Methods("GET")
		router.HandleFunc("/apis/storage.k8s.io/v1/volumeattachments", fakeAPI.HandleListVolumeAttachments).Methods("GET")
		
		// Policy API
		router.HandleFunc("/apis/policy/v1/poddisruptionbudgets", fakeAPI.HandleListPodDisruptionBudgets).Methods("GET")
		
		// Batch API
		router.HandleFunc("/apis/batch/v1/jobs", fakeAPI.HandleListJobs).Methods("GET")
	}

	// Start server
	log.Printf("Serving HTTP API on %s\n", port)
	log.Printf("Fake Kubernetes API server ready for kube-scheduler connections")
	log.Printf("Start kube-scheduler with: --master http://localhost%s --leader-elect=false", port)

	log.Fatal(http.ListenAndServe("0.0.0.0"+port, router))
}
