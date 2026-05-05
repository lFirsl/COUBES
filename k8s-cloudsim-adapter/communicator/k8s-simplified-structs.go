package communicator

import "time"

// CsNode represents a simulated Kubernetes CsNode (which maps to a CloudSim VM or Container).
type CsNode struct {
	ID       int               `json:"id"`
	Name     string            `json:"name"`
	MIPSAval int               `json:"mipsAvailable"`
	RAMAval  int               `json:"ramAvailable"`
	Pes      int               `json:"pes"`
	BW       int64             `json:"bw"`
	Size     int64             `json:"size"`
	Type     string            `json:"type"`
	Labels   map[string]string `json:"labels,omitempty"`
}

// CsPod represents a simulated Kubernetes CsPod (which maps to a CloudSim Cloudlet).
type CsPod struct {
	ID             int     `json:"id"`
	Name           string  `json:"name"`
	Length         int64   `json:"length"`
	Pes            int     `json:"pes"`
	RamRequest     int     `json:"ramRequest"`
	FileSize       int64   `json:"fileSize"`
	OutputSize     int64   `json:"outputSize"`
	UtilizationCPU float64 `json:"utilizationCpu"`
	UtilizationRAM float64 `json:"utilizationRam"`
	UtilizationBW  float64 `json:"utilizationBw"`

	Status        string `json:"status"`
	NodeName      string `json:"nodeName,omitempty"`
	NodeID        int    `json:"vmId"`
	SchedulerName string `json:"schedulerName,omitempty"`

	Labels             map[string]string `json:"labels,omitempty"`
	AffinityGroup      string            `json:"affinityGroup,omitempty"`
	AntiAffinityGroup  string            `json:"antiAffinityGroup,omitempty"`
	HardAffinity       *bool             `json:"hardAffinity,omitempty"`     // nil = true (hard)
	HardAntiAffinity   *bool             `json:"hardAntiAffinity,omitempty"` // nil = true (hard)
	Queue              string            `json:"queue,omitempty"`            // Volcano queue name (default: "default")
	GangId             string            `json:"gangId,omitempty"`           // Gang scheduling: pods with same gangId form a PodGroup
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
