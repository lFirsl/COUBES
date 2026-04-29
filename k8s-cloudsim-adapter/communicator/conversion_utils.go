package communicator

import (
	"fmt"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/types"
	"regexp"
	"strconv"
	"strings"
)

// BuildNode constructs a v1.Node from a CsNode without KWOK-specific fields.
// The node is ready for scheduling by a real kube-scheduler.
func BuildNode(csNode CsNode, schedulerName string) *corev1.Node {
	cpuStr := fmt.Sprintf("%d", csNode.Pes)
	ramStr := fmt.Sprintf("%dMi", csNode.RAMAval)

	nodeLabels := map[string]string{
		"kubernetes.io/hostname": csNode.Name,
		"kubernetes.io/arch":     "amd64",
		"kubernetes.io/os":       "linux",
	}
	for k, v := range csNode.Labels {
		nodeLabels[k] = v
	}

	return &corev1.Node{
		TypeMeta: metav1.TypeMeta{
			Kind:       "Node",
			APIVersion: "v1",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:   fmt.Sprintf("csnode-%d", csNode.ID),
			UID:    types.UID(fmt.Sprintf("csnode-%d-uid", csNode.ID)),
			Labels: nodeLabels,
			Annotations: map[string]string{
				"cloudsim.io/id":   fmt.Sprintf("%d", csNode.ID),
				"cloudsim.io/type": csNode.Type,
				"cloudsim.io/bw":   fmt.Sprintf("%d", csNode.BW),
				"cloudsim.io/size": fmt.Sprintf("%d", csNode.Size),
			},
		},
		Spec: corev1.NodeSpec{
			// No KWOK taints
		},
		Status: corev1.NodeStatus{
			Phase: corev1.NodeRunning,
			Conditions: []corev1.NodeCondition{
				{
					Type:   corev1.NodeReady,
					Status: corev1.ConditionTrue,
				},
			},
			Allocatable: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse(cpuStr),
				corev1.ResourceMemory: resource.MustParse(ramStr),
				corev1.ResourcePods:   resource.MustParse("110"),
			},
			Capacity: corev1.ResourceList{
				corev1.ResourceCPU:    resource.MustParse(cpuStr),
				corev1.ResourceMemory: resource.MustParse(ramStr),
				corev1.ResourcePods:   resource.MustParse("110"),
			},
		},
	}
}

// BuildPod constructs a v1.Pod from a CsPod without KWOK-specific fields.
// The pod is ready for scheduling by a real kube-scheduler.
func BuildPod(csPod CsPod, schedulerName string) *corev1.Pod {
	cpuStr := fmt.Sprintf("%d", csPod.Pes)

	requests := corev1.ResourceList{
		corev1.ResourceCPU: resource.MustParse(cpuStr),
	}
	if csPod.RamRequest > 0 {
		requests[corev1.ResourceMemory] = resource.MustParse(fmt.Sprintf("%dMi", csPod.RamRequest))
	}

	podLabels := make(map[string]string)
	for k, v := range csPod.Labels {
		podLabels[k] = v
	}
	// Affinity groups use a well-known label so the affinity rules can match on it
	if csPod.AffinityGroup != "" {
		podLabels["coubes.io/affinity-group"] = csPod.AffinityGroup
	}
	if csPod.AntiAffinityGroup != "" {
		podLabels["coubes.io/anti-affinity-group"] = csPod.AntiAffinityGroup
	}

	var affinity *corev1.Affinity
	if csPod.AffinityGroup != "" || csPod.AntiAffinityGroup != "" {
		affinity = &corev1.Affinity{}
		if csPod.AffinityGroup != "" {
			hard := csPod.HardAffinity == nil || *csPod.HardAffinity
			term := corev1.PodAffinityTerm{
				LabelSelector: &metav1.LabelSelector{
					MatchLabels: map[string]string{
						"coubes.io/affinity-group": csPod.AffinityGroup,
					},
				},
				TopologyKey: "kubernetes.io/hostname",
			}
			affinity.PodAffinity = &corev1.PodAffinity{}
			if hard {
				affinity.PodAffinity.RequiredDuringSchedulingIgnoredDuringExecution = []corev1.PodAffinityTerm{term}
			} else {
				affinity.PodAffinity.PreferredDuringSchedulingIgnoredDuringExecution = []corev1.WeightedPodAffinityTerm{
					{Weight: 100, PodAffinityTerm: term},
				}
			}
		}
		if csPod.AntiAffinityGroup != "" {
			hard := csPod.HardAntiAffinity == nil || *csPod.HardAntiAffinity
			term := corev1.PodAffinityTerm{
				LabelSelector: &metav1.LabelSelector{
					MatchLabels: map[string]string{
						"coubes.io/anti-affinity-group": csPod.AntiAffinityGroup,
					},
				},
				TopologyKey: "kubernetes.io/hostname",
			}
			affinity.PodAntiAffinity = &corev1.PodAntiAffinity{}
			if hard {
				affinity.PodAntiAffinity.RequiredDuringSchedulingIgnoredDuringExecution = []corev1.PodAffinityTerm{term}
			} else {
				affinity.PodAntiAffinity.PreferredDuringSchedulingIgnoredDuringExecution = []corev1.WeightedPodAffinityTerm{
					{Weight: 100, PodAffinityTerm: term},
				}
			}
		}
	}

	podName := fmt.Sprintf("cspod-%d", csPod.ID)
	annotations := map[string]string{
		"cloudsim.io/id": fmt.Sprintf("%d", csPod.ID),
	}
	// Volcano requires every pod to carry this annotation pointing to its PodGroup.
	// Without it, getJobID() returns "" and Volcano refuses to schedule the pod.
	if schedulerName == "volcano" {
		annotations["scheduling.k8s.io/group-name"] = podName
	}

	pod := &corev1.Pod{
		TypeMeta: metav1.TypeMeta{
			Kind:       "Pod",
			APIVersion: "v1",
		},
		ObjectMeta: metav1.ObjectMeta{
			Name:        podName,
			Namespace:   "default",
			UID:         types.UID(fmt.Sprintf("cspod-%d-uid", csPod.ID)),
			Annotations: annotations,
		},
		Spec: corev1.PodSpec{
			SchedulerName: schedulerName,
			Affinity:      affinity,
			Containers: []corev1.Container{
				{
					Name:  "fake-container",
					Image: "fake-image",
					Resources: corev1.ResourceRequirements{
						Requests: requests,
					},
				},
			},
		},
	}
	if len(podLabels) > 0 {
		pod.ObjectMeta.Labels = podLabels
	}
	// Volcano's getTaskStatus() maps pod.Status.Phase="" to Unknown, which blocks scheduling.
	// Setting PodPending ensures tasks are classified as Pending and eligible for allocation.
	pod.Status.Phase = corev1.PodPending
	return pod
}

// ConvertToCsPod converts a Kubernetes v1.Pod back to a CsPod.
// Used for reading pod state from the InMemoryStore.
func ConvertToCsPod(k8sPod *corev1.Pod) CsPod {
	id := 0
	nodeID := -1
	status := "Unschedulable"

	// Extract ID from pod name like "cspod-42"
	if strings.HasPrefix(k8sPod.Name, "cspod-") {
		if parsed, err := strconv.Atoi(strings.TrimPrefix(k8sPod.Name, "cspod-")); err == nil {
			id = parsed
		}
	}

	// Optional fallback: extract ID from annotation
	if val, ok := k8sPod.Annotations["cloudsim.io/id"]; ok {
		if parsed, err := strconv.Atoi(val); err == nil {
			id = parsed
		}
	}

	// Extract NodeID from annotation
	if k8sPod.Spec.NodeName != "" {
		re := regexp.MustCompile(`csnode-(\d+)`)
		matches := re.FindStringSubmatch(k8sPod.Spec.NodeName)
		if len(matches) == 2 {
			if parsed, err := strconv.Atoi(matches[1]); err == nil {
				nodeID = parsed
				status = "Scheduled"
			}
		}
	}

	return CsPod{
		ID:            id,
		Name:          k8sPod.Name,
		Status:        status,
		NodeName:      k8sPod.Spec.NodeName,
		NodeID:        nodeID,
		SchedulerName: k8sPod.Spec.SchedulerName,
	}
}

// ConvertToCsPods converts a slice of Kubernetes v1.Pod to CsPod slice.
func ConvertToCsPods(k8sPods []*corev1.Pod) []CsPod {
	var csPods []CsPod
	for _, pod := range k8sPods {
		csPods = append(csPods, ConvertToCsPod(pod))
	}
	return csPods
}

// ConvertToCsNode converts a Kubernetes v1.Node back to a CsNode.
// Used for reading node state from the InMemoryStore.
func ConvertToCsNode(k8sNode *corev1.Node) CsNode {
	id := 0
	mips := 0
	ram := 0
	bw := 0
	size := 0
	nodeType := ""

	// Extract ID from name like "csnode-42"
	if strings.HasPrefix(k8sNode.Name, "csnode-") {
		if parsed, err := strconv.Atoi(strings.TrimPrefix(k8sNode.Name, "csnode-")); err == nil {
			id = parsed
		}
	}

	// Fallback: extract ID from annotation
	if val, ok := k8sNode.Annotations["cloudsim.io/id"]; ok {
		if parsed, err := strconv.Atoi(val); err == nil {
			id = parsed
		}
	}

	// Extract MIPS (CPU in millicores)
	if cpuQty, ok := k8sNode.Status.Capacity[corev1.ResourceCPU]; ok {
		mips = int(cpuQty.MilliValue())
	}

	// Extract RAM (in MiB)
	if memQty, ok := k8sNode.Status.Capacity[corev1.ResourceMemory]; ok {
		ram = int(memQty.Value() / (1024 * 1024))
	}

	// Extract annotations for BW, Size, Type
	if val, ok := k8sNode.Annotations["cloudsim.io/bw"]; ok {
		if parsed, err := strconv.Atoi(val); err == nil {
			bw = parsed
		}
	}

	if val, ok := k8sNode.Annotations["cloudsim.io/size"]; ok {
		if parsed, err := strconv.Atoi(val); err == nil {
			size = parsed
		}
	}

	if val, ok := k8sNode.Annotations["cloudsim.io/type"]; ok {
		nodeType = val
	}

	return CsNode{
		ID:       id,
		Name:     k8sNode.Name,
		MIPSAval: mips,
		RAMAval:  ram,
		BW:       int64(bw),
		Size:     int64(size),
		Type:     nodeType,
	}
}

// ConvertToCsNodes converts a slice of Kubernetes v1.Node to CsNode slice.
func ConvertToCsNodes(k8sNodes []*corev1.Node) []CsNode {
	var csNodes []CsNode
	for _, node := range k8sNodes {
		csNodes = append(csNodes, ConvertToCsNode(node))
	}
	return csNodes
}
