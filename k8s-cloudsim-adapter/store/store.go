package store

import (
	"context"
	"fmt"
	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
	"sync"
)

// InMemoryStore holds all node and pod state in process memory.
// It is safe for concurrent access.
type InMemoryStore struct {
	mu              sync.RWMutex
	nodes           map[string]*corev1.Node
	pods            map[string]*corev1.Pod
	resourceVersion int64

	nodeEventCh     chan metav1.WatchEvent
	podEventCh      chan metav1.WatchEvent
	nodeBroadcaster *BroadcastServer[metav1.WatchEvent]
	podBroadcaster  *BroadcastServer[metav1.WatchEvent]

	ctx    context.Context
	cancel context.CancelFunc
}

// NewInMemoryStore creates a new empty InMemoryStore.
func NewInMemoryStore() *InMemoryStore {
	ctx, cancel := context.WithCancel(context.Background())
	nodeEventCh := make(chan metav1.WatchEvent, 1000)
	podEventCh := make(chan metav1.WatchEvent, 1000)
	return &InMemoryStore{
		nodes:           make(map[string]*corev1.Node),
		pods:            make(map[string]*corev1.Pod),
		nodeEventCh:     nodeEventCh,
		podEventCh:      podEventCh,
		nodeBroadcaster: NewBroadcastServer[metav1.WatchEvent](ctx, nodeEventCh),
		podBroadcaster:  NewBroadcastServer[metav1.WatchEvent](ctx, podEventCh),
		ctx:             ctx,
		cancel:          cancel,
	}
}

// SubscribeNodes returns a channel that receives node WatchEvents.
func (s *InMemoryStore) SubscribeNodes() <-chan metav1.WatchEvent {
	return s.nodeBroadcaster.Subscribe()
}

// CancelNodeSubscription cancels a node subscription.
func (s *InMemoryStore) CancelNodeSubscription(ch <-chan metav1.WatchEvent) {
	s.nodeBroadcaster.CancelSubscription(ch)
}

// SubscribePods returns a channel that receives pod WatchEvents.
func (s *InMemoryStore) SubscribePods() <-chan metav1.WatchEvent {
	return s.podBroadcaster.Subscribe()
}

// CancelPodSubscription cancels a pod subscription.
func (s *InMemoryStore) CancelPodSubscription(ch <-chan metav1.WatchEvent) {
	s.podBroadcaster.CancelSubscription(ch)
}

// --- Node operations ---

// CreateNode adds a node to the store.
func (s *InMemoryStore) CreateNode(node *corev1.Node) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	node.ResourceVersion = rv
	nodeCopy := node.DeepCopy()
	s.nodes[node.Name] = nodeCopy
	s.nodeEventCh <- metav1.WatchEvent{
		Type:   "ADDED",
		Object: runtime.RawExtension{Object: nodeCopy},
	}
}

// GetNodes returns all nodes in the store.
func (s *InMemoryStore) GetNodes() []*corev1.Node {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]*corev1.Node, 0, len(s.nodes))
	for _, n := range s.nodes {
		result = append(result, n.DeepCopy())
	}
	return result
}

// GetNode returns a node by name.
func (s *InMemoryStore) GetNode(name string) (*corev1.Node, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	n, ok := s.nodes[name]
	if !ok {
		return nil, false
	}
	return n.DeepCopy(), true
}

// UpdateNode updates an existing node in the store.
func (s *InMemoryStore) UpdateNode(name string, node *corev1.Node) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	node.ResourceVersion = rv
	nodeCopy := node.DeepCopy()
	s.nodes[name] = nodeCopy
	s.nodeEventCh <- metav1.WatchEvent{
		Type:   "MODIFIED",
		Object: runtime.RawExtension{Object: nodeCopy},
	}
}

// DeleteNode removes a node from the store.
func (s *InMemoryStore) DeleteNode(name string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.nodes[name]; !ok {
		return
	}
	deletedNode := s.nodes[name].DeepCopy()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	deletedNode.ResourceVersion = rv
	delete(s.nodes, name)
	s.nodeEventCh <- metav1.WatchEvent{
		Type:   "DELETED",
		Object: runtime.RawExtension{Object: deletedNode},
	}
}

// --- Pod operations ---

// CreatePod adds a pod to the store.
func (s *InMemoryStore) CreatePod(pod *corev1.Pod) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	pod.ResourceVersion = rv
	podCopy := pod.DeepCopy()
	s.pods[pod.Name] = podCopy
	s.podEventCh <- metav1.WatchEvent{
		Type:   "ADDED",
		Object: runtime.RawExtension{Object: podCopy},
	}
}

// GetPods returns all pods in the store.
func (s *InMemoryStore) GetPods() []*corev1.Pod {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]*corev1.Pod, 0, len(s.pods))
	for _, p := range s.pods {
		result = append(result, p.DeepCopy())
	}
	return result
}

// GetPod returns a pod by name.
func (s *InMemoryStore) GetPod(name string) (*corev1.Pod, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.pods[name]
	if !ok {
		return nil, false
	}
	return p.DeepCopy(), true
}

// UpdatePod updates an existing pod in the store.
func (s *InMemoryStore) UpdatePod(name string, pod *corev1.Pod) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	pod.ResourceVersion = rv
	podCopy := pod.DeepCopy()
	s.pods[name] = podCopy
	s.podEventCh <- metav1.WatchEvent{
		Type:   "MODIFIED",
		Object: runtime.RawExtension{Object: podCopy},
	}
}

// DeletePod removes a pod from the store.
func (s *InMemoryStore) DeletePod(name string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if _, ok := s.pods[name]; !ok {
		return
	}
	deletedPod := s.pods[name].DeepCopy()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	deletedPod.ResourceVersion = rv
	delete(s.pods, name)
	s.podEventCh <- metav1.WatchEvent{
		Type:   "DELETED",
		Object: runtime.RawExtension{Object: deletedPod},
	}
}

// GetResourceVersion returns the current resource version.
func (s *InMemoryStore) GetResourceVersion() int64 {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.resourceVersion
}

// Reset atomically clears all state, resets resourceVersion to 0,
// and recreates broadcast channels.
func (s *InMemoryStore) Reset() {
	s.mu.Lock()
	defer s.mu.Unlock()

	// Cancel old broadcaster goroutines
	s.cancel()

	// Drain and close old event channels
	close(s.nodeEventCh)
	close(s.podEventCh)

	// Clear state
	s.nodes = make(map[string]*corev1.Node)
	s.pods = make(map[string]*corev1.Pod)
	s.resourceVersion = 0

	// Recreate channels and broadcasters
	s.ctx, s.cancel = context.WithCancel(context.Background())
	s.nodeEventCh = make(chan metav1.WatchEvent, 1000)
	s.podEventCh = make(chan metav1.WatchEvent, 1000)
	s.nodeBroadcaster = NewBroadcastServer[metav1.WatchEvent](s.ctx, s.nodeEventCh)
	s.podBroadcaster = NewBroadcastServer[metav1.WatchEvent](s.ctx, s.podEventCh)
}
