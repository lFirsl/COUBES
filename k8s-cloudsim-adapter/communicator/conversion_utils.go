package communicator

import (
	"fmt"
	corev1 "k8s.io/api/core/v1"
	"k8s.io/apimachinery/pkg/api/resource"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"regexp"
	"strconv"
	"strings"
)

// CreateFakePod creates a KWOK-compatible fake pod with predefined spec
func (c *Communicator) SendFakePodFromCs(csPod CsPod) error {
	cpuStr := fmt.Sprintf("%d", csPod.Pes)

	pod := &corev1.Pod{
		ObjectMeta: metav1.ObjectMeta{
			Name:      fmt.Sprintf("cspod-%d", csPod.ID),
			Namespace: "default",
			Labels: map[string]string{
				"app": csPod.Name,
			},
			Annotations: map[string]string{
				"cloudsim.io/id": fmt.Sprintf("%d", csPod.ID),
			},
		},
		Spec: corev1.PodSpec{
			SchedulerName: c.kubeClient.SchedulerName(),
			Affinity: &corev1.Affinity{
				NodeAffinity: &corev1.NodeAffinity{
					RequiredDuringSchedulingIgnoredDuringExecution: &corev1.NodeSelector{
						NodeSelectorTerms: []corev1.NodeSelectorTerm{
							{
								MatchExpressions: []corev1.NodeSelectorRequirement{
									{
										Key:      "type",
										Operator: corev1.NodeSelectorOpIn,
										Values:   []string{"kwok"},
									},
								},
							},
						},
					},
				},
			},
			Tolerations: []corev1.Toleration{
				{
					Key:      "kwok.x-k8s.io/node",
					Operator: corev1.TolerationOpExists,
					Effect:   corev1.TaintEffectNoSchedule,
				},
			},
			Containers: []corev1.Container{
				{
					Name:  "fake-container",
					Image: "fake-image",
					Resources: corev1.ResourceRequirements{
						Requests: corev1.ResourceList{
							corev1.ResourceCPU: resource.MustParse(cpuStr),
						},
						Limits: corev1.ResourceList{
							corev1.ResourceCPU: resource.MustParse(cpuStr),
						},
					},
				},
			},
		},
	}

	err := c.kubeClient.SendPod(pod)
	if err != nil {
		return err
	}
	return nil
}

func (c *Communicator) SendFakePodsFromCs(csPods []CsPod) error {
	for _, csPod := range csPods {
		if err := c.SendFakePodFromCs(csPod); err != nil {
			return fmt.Errorf("failed to create pod %s: %w", csPod.Name, err)
		}
	}
	return nil
}

func (c *Communicator) SendFakeNodeFromCs(csNode CsNode) error {
	cpuStr := fmt.Sprintf("%d", csNode.Pes)
	ramStr := fmt.Sprintf("%dMi", csNode.RAMAval)

	node := &corev1.Node{
		ObjectMeta: metav1.ObjectMeta{
			Name: fmt.Sprintf("csnode-%d", csNode.ID),
			Labels: map[string]string{
				"beta.kubernetes.io/arch":       "amd64",
				"beta.kubernetes.io/os":         "linux",
				"kubernetes.io/arch":            "amd64",
				"kubernetes.io/hostname":        csNode.Name,
				"kubernetes.io/os":              "linux",
				"kubernetes.io/role":            "agent",
				"node-role.kubernetes.io/agent": "",
				"type":                          "kwok",
			},
			Annotations: map[string]string{
				"kwok.x-k8s.io/node":           "fake",
				"node.alpha.kubernetes.io/ttl": "0",
				"cloudsim.io/id":               fmt.Sprintf("%d", csNode.ID),
				"cloudsim.io/type":             csNode.Type,
				"cloudsim.io/bw":               fmt.Sprintf("%d", csNode.BW),
				"cloudsim.io/size":             fmt.Sprintf("%d", csNode.Size),
			},
		},
		Spec: corev1.NodeSpec{
			Taints: []corev1.Taint{
				{
					Key:    "kwok.x-k8s.io/node",
					Value:  "fake",
					Effect: corev1.TaintEffectNoSchedule,
				},
			},
		},
		Status: corev1.NodeStatus{
			Phase: corev1.NodeRunning,
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
			NodeInfo: corev1.NodeSystemInfo{
				Architecture:     "amd64",
				OperatingSystem:  "linux",
				KubeletVersion:   "fake",
				KubeProxyVersion: "fake",
			},
		},
	}

	err := c.kubeClient.SendNode(node)
	if err != nil {
		return err
	}
	return nil
}

func (c *Communicator) SendFakeNodesFromCs(csNodes []CsNode) error {
	for _, csNode := range csNodes {
		if err := c.SendFakeNodeFromCs(csNode); err != nil {
			return fmt.Errorf("failed to create node %s: %w", csNode.Name, err)
		}
	}
	return nil
}

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
		Status:        status, //string(k8sPod.Status.Phase)
		NodeName:      k8sPod.Spec.NodeName,
		NodeID:        nodeID,
		SchedulerName: k8sPod.Spec.SchedulerName,
	}
}

func ConvertToCsPods(k8sPods []*corev1.Pod) []CsPod {
	var csPods []CsPod
	for _, pod := range k8sPods {
		csPods = append(csPods, ConvertToCsPod(pod))
	}
	return csPods
}

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

func ConvertToCsNodes(k8sNodes []*corev1.Node) []CsNode {
	var csNodes []CsNode
	for _, node := range k8sNodes {
		csNodes = append(csNodes, ConvertToCsNode(node))
	}
	return csNodes
}
