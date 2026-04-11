package communicator

import "time"

// CsNode represents a simulated Kubernetes CsNode (which maps to a CloudSim VM or Container).
type CsNode struct {
	ID       int    `json:"id"`            // Unique identifier for the node (CloudSim VM/Container ID)
	Name     string `json:"name"`          // Name of the node (e.g., "vm-0", "container-1")
	MIPSAval int    `json:"mipsAvailable"` // Available MIPS on this node
	RAMAval  int    `json:"ramAvailable"`  // Available RAM on this node (in MB)

	Pes  int    `json:"pes"`  // Number of processing elements
	BW   int64  `json:"bw"`   // Bandwidth
	Size int64  `json:"size"` // Storage size
	Type string `json:"type"` // "vm" or "container"
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

// --- Batch Protocol Structs ---

// SimulationSnapshot represents the complete state of nodes and pending pods sent by the Broker
// to the Adapter at the start of each SchedulingRound.
type SimulationSnapshot struct {
	Nodes           []CsNode `json:"nodes"`           // All active VMs as CsNode objects
	Pods            []CsPod  `json:"pods"`            // All pending cloudlets as CsPod objects
	CompletedPodIDs []int    `json:"completedPodIds"` // Cloudlet IDs that have completed since the last round
}

// BatchDecision represents the complete set of pod-to-node assignments (and failures) returned by
// the Adapter to the Broker at the end of a SchedulingRound.
type BatchDecision struct {
	Scheduled     []PodAssignment `json:"scheduled"`     // Successfully scheduled pods
	Unschedulable []PodFailure    `json:"unschedulable"` // Pods that could not be scheduled
}

// PodAssignment represents a single pod-to-node assignment with binding timestamp.
type PodAssignment struct {
	PodID            int       `json:"podId"`            // CloudSim cloudlet ID
	NodeID           int       `json:"nodeId"`           // CloudSim VM ID
	BindingTimestamp time.Time `json:"bindingTimestamp"` // Wall-clock time when binding was recorded
}

// PodFailure represents a pod that could not be scheduled.
type PodFailure struct {
	PodID  int    `json:"podId"` // CloudSim cloudlet ID
	Reason string `json:"reason"` // Failure reason from scheduler
}
