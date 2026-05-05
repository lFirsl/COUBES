package communicator

import (
	"fmt"
	"testing"

	corev1 "k8s.io/api/core/v1"

	"k8s-cloudsim-adapter/scheduler"
	"k8s-cloudsim-adapter/store"
)

// Test that buildPodGroup sets the queue field correctly.
func TestBuildPodGroup_SetsQueueField(t *testing.T) {
	pg := buildPodGroup("cspod-1", "default", "high-priority")

	spec := pg["spec"].(map[string]interface{})
	if spec["queue"] != "high-priority" {
		t.Fatalf("expected queue 'high-priority', got %v", spec["queue"])
	}
	if spec["minMember"] != 1 {
		t.Fatalf("expected minMember 1, got %v", spec["minMember"])
	}
}

// Test that buildPodGroup defaults to "default" queue when empty string is passed.
func TestBuildPodGroup_DefaultsToDefaultQueue(t *testing.T) {
	pg := buildPodGroup("cspod-2", "default", "")

	spec := pg["spec"].(map[string]interface{})
	if spec["queue"] != "default" {
		t.Fatalf("expected queue 'default', got %v", spec["queue"])
	}
}

// Test that buildPodGroup sets status.phase to Pending (required for Volcano's enqueue action).
func TestBuildPodGroup_StatusIsPending(t *testing.T) {
	pg := buildPodGroup("cspod-3", "default", "batch")

	status := pg["status"].(map[string]interface{})
	if status["phase"] != "Pending" {
		t.Fatalf("expected phase 'Pending', got %v", status["phase"])
	}
}

// Test that BuildPod adds the Volcano group-name annotation when schedulerName is "volcano".
func TestBuildPod_VolcanoAnnotation(t *testing.T) {
	csPod := CsPod{ID: 5, Name: "cspod-5", Pes: 2}
	pod := BuildPod(csPod, "volcano")

	groupName, ok := pod.Annotations["scheduling.k8s.io/group-name"]
	if !ok {
		t.Fatal("expected scheduling.k8s.io/group-name annotation for Volcano")
	}
	if groupName != "cspod-5" {
		t.Fatalf("expected group-name 'cspod-5', got %s", groupName)
	}
}

// Test that BuildPod does NOT add the Volcano annotation for kube-scheduler.
func TestBuildPod_NoVolcanoAnnotationForKubeScheduler(t *testing.T) {
	csPod := CsPod{ID: 5, Name: "cspod-5", Pes: 2}
	pod := BuildPod(csPod, "default-scheduler")

	if _, ok := pod.Annotations["scheduling.k8s.io/group-name"]; ok {
		t.Fatal("should not have scheduling.k8s.io/group-name annotation for kube-scheduler")
	}
}

// Test that BuildPod sets PodPending phase (required for Volcano task classification).
func TestBuildPod_PhaseIsPending(t *testing.T) {
	csPod := CsPod{ID: 1, Name: "cspod-1", Pes: 1}
	pod := BuildPod(csPod, "volcano")

	if pod.Status.Phase != corev1.PodPending {
		t.Fatalf("expected PodPending phase, got %s", pod.Status.Phase)
	}
}

// Test that BuildPod sets the correct schedulerName.
func TestBuildPod_SchedulerName(t *testing.T) {
	csPod := CsPod{ID: 1, Name: "cspod-1", Pes: 1}

	pod := BuildPod(csPod, "volcano")
	if pod.Spec.SchedulerName != "volcano" {
		t.Fatalf("expected schedulerName 'volcano', got %s", pod.Spec.SchedulerName)
	}

	pod2 := BuildPod(csPod, "my-scheduler")
	if pod2.Spec.SchedulerName != "my-scheduler" {
		t.Fatalf("expected schedulerName 'my-scheduler', got %s", pod2.Spec.SchedulerName)
	}
}

// Test that BuildPod sets CPU requests from Pes field.
func TestBuildPod_CPURequest(t *testing.T) {
	csPod := CsPod{ID: 1, Name: "cspod-1", Pes: 4}
	pod := BuildPod(csPod, "volcano")

	cpu := pod.Spec.Containers[0].Resources.Requests[corev1.ResourceCPU]
	if cpu.String() != "4" {
		t.Fatalf("expected CPU request '4', got %s", cpu.String())
	}
}

// Test that ensureVolcanoQueue is idempotent — calling twice doesn't panic or duplicate.
func TestEnsureVolcanoQueue_Idempotent(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	round := &scheduler.SchedulingRound{}
	comm := NewCommunicator(s, round, "volcano", false)

	comm.ensureVolcanoQueue("high-priority")
	comm.ensureVolcanoQueue("high-priority") // second call should be a no-op

	queues := s.GetQueues()
	count := 0
	for _, q := range queues {
		meta := q["metadata"].(map[string]interface{})
		if meta["name"] == "high-priority" {
			count++
		}
	}
	if count != 1 {
		t.Fatalf("expected exactly 1 high-priority queue, got %d", count)
	}
}

// Test that ensureVolcanoQueue skips the "default" queue (Volcano creates it).
func TestEnsureVolcanoQueue_SkipsDefault(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	round := &scheduler.SchedulingRound{}
	comm := NewCommunicator(s, round, "volcano", false)

	comm.ensureVolcanoQueue("default")

	_, exists := s.GetQueue("default")
	if exists {
		t.Fatal("ensureVolcanoQueue should not create the 'default' queue")
	}
}

// Test that ensureVolcanoQueue uses the correct weight from volcanoQueueWeights.
func TestEnsureVolcanoQueue_CorrectWeights(t *testing.T) {
	s := store.NewInMemoryStore()
	defer s.Reset()
	round := &scheduler.SchedulingRound{}
	comm := NewCommunicator(s, round, "volcano", false)

	comm.ensureVolcanoQueue("high-priority")
	comm.ensureVolcanoQueue("batch")

	hp, _ := s.GetQueue("high-priority")
	hpSpec := hp["spec"].(map[string]interface{})
	if fmt.Sprintf("%v", hpSpec["weight"]) != "3" {
		t.Fatalf("expected high-priority weight 3, got %v", hpSpec["weight"])
	}

	batch, _ := s.GetQueue("batch")
	batchSpec := batch["spec"].(map[string]interface{})
	if fmt.Sprintf("%v", batchSpec["weight"]) != "1" {
		t.Fatalf("expected batch weight 1, got %v", batchSpec["weight"])
	}
}
