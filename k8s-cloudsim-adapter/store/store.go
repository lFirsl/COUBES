package store

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	corev1 "k8s.io/api/core/v1"
	metav1 "k8s.io/apimachinery/pkg/apis/meta/v1"
	"k8s.io/apimachinery/pkg/runtime"
)

// InMemoryStore holds all node and pod state in process memory.
// It is safe for concurrent access.
type InMemoryStore struct {
	mu              sync.RWMutex
	nodes           map[string]*corev1.Node
	pods            map[string]*corev1.Pod
	queues          map[string]map[string]interface{} // Volcano Queue CRDs keyed by name
	podGroups       map[string]map[string]interface{} // Volcano PodGroup CRDs keyed by "namespace/name"
	resourceVersion int64

	nodeEventCh       chan metav1.WatchEvent
	podEventCh        chan metav1.WatchEvent
	queueEventCh      chan metav1.WatchEvent
	podGroupEventCh   chan metav1.WatchEvent
	nodeBroadcaster   *BroadcastServer[metav1.WatchEvent]
	podBroadcaster    *BroadcastServer[metav1.WatchEvent]
	queueBroadcaster  *BroadcastServer[metav1.WatchEvent]
	podGroupBroadcaster *BroadcastServer[metav1.WatchEvent]

	ctx    context.Context
	cancel context.CancelFunc
}

// NewInMemoryStore creates a new empty InMemoryStore.
func NewInMemoryStore() *InMemoryStore {
	ctx, cancel := context.WithCancel(context.Background())
	nodeEventCh := make(chan metav1.WatchEvent, 1000)
	podEventCh := make(chan metav1.WatchEvent, 1000)
	queueEventCh := make(chan metav1.WatchEvent, 100)
	podGroupEventCh := make(chan metav1.WatchEvent, 1000)
	return &InMemoryStore{
		nodes:               make(map[string]*corev1.Node),
		pods:                make(map[string]*corev1.Pod),
		queues:              make(map[string]map[string]interface{}),
		podGroups:           make(map[string]map[string]interface{}),
		nodeEventCh:         nodeEventCh,
		podEventCh:          podEventCh,
		queueEventCh:        queueEventCh,
		podGroupEventCh:     podGroupEventCh,
		nodeBroadcaster:     NewBroadcastServer[metav1.WatchEvent](ctx, nodeEventCh),
		podBroadcaster:      NewBroadcastServer[metav1.WatchEvent](ctx, podEventCh),
		queueBroadcaster:    NewBroadcastServer[metav1.WatchEvent](ctx, queueEventCh),
		podGroupBroadcaster: NewBroadcastServer[metav1.WatchEvent](ctx, podGroupEventCh),
		ctx:                 ctx,
		cancel:              cancel,
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

// DeleteAll emits DELETED watch events for every pod and node currently in the store,
// then removes them. This allows the kube-scheduler's internal cache to drain cleanly
// before a Reset() recreates the broadcast channels.
func (s *InMemoryStore) DeleteAll() {
	s.mu.Lock()
	defer s.mu.Unlock()

	for name, pod := range s.pods {
		deletedPod := pod.DeepCopy()
		s.resourceVersion++
		deletedPod.ResourceVersion = fmt.Sprintf("%d", s.resourceVersion)
		delete(s.pods, name)
		s.podEventCh <- metav1.WatchEvent{
			Type:   "DELETED",
			Object: runtime.RawExtension{Object: deletedPod},
		}
	}

	for name, node := range s.nodes {
		deletedNode := node.DeepCopy()
		s.resourceVersion++
		deletedNode.ResourceVersion = fmt.Sprintf("%d", s.resourceVersion)
		delete(s.nodes, name)
		s.nodeEventCh <- metav1.WatchEvent{
			Type:   "DELETED",
			Object: runtime.RawExtension{Object: deletedNode},
		}
	}
}

// Reset clears all state and resets resourceVersion to 0.
// Uses DeleteAll() to emit proper DELETED watch events so that
// kube-scheduler's informer caches stay in sync. Watch connections
// and broadcast channels are preserved.
func (s *InMemoryStore) Reset() {
	s.DeleteAll()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion = 0
}

// --- Volcano Queue operations ---
// Queues are Volcano CRDs (scheduling.volcano.sh/v1beta1). Volcano creates a "default"
// and "root" queue on startup before it will schedule any pods. We store them as plain
// maps so we don't need to import the Volcano API types — the handlers use map[string]interface{}.

// volcanoQueue is a raw JSON-serialisable representation of a Volcano Queue.
type volcanoQueue struct {
	Name            string
	ResourceVersion string
	Raw             map[string]interface{}
}

// CreateQueue stores a new Volcano Queue and broadcasts an ADDED watch event.
func (s *InMemoryStore) CreateQueue(name string, raw map[string]interface{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	raw["metadata"].(map[string]interface{})["resourceVersion"] = rv
	s.queues[name] = raw
	s.queueEventCh <- metav1.WatchEvent{
		Type:   "ADDED",
		Object: runtime.RawExtension{Raw: mustMarshal(raw)},
	}
}

// GetQueue returns a queue by name.
func (s *InMemoryStore) GetQueue(name string) (map[string]interface{}, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	q, ok := s.queues[name]
	if !ok {
		return nil, false
	}
	return copyMap(q), true
}

// GetQueues returns all queues.
func (s *InMemoryStore) GetQueues() []map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]map[string]interface{}, 0, len(s.queues))
	for _, q := range s.queues {
		result = append(result, copyMap(q))
	}
	return result
}

// UpdateQueueStatus updates a queue's status sub-resource and broadcasts MODIFIED.
func (s *InMemoryStore) UpdateQueueStatus(name string, status map[string]interface{}) (map[string]interface{}, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	q, ok := s.queues[name]
	if !ok {
		return nil, false
	}
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	q["metadata"].(map[string]interface{})["resourceVersion"] = rv
	q["status"] = status
	s.queueEventCh <- metav1.WatchEvent{
		Type:   "MODIFIED",
		Object: runtime.RawExtension{Raw: mustMarshal(q)},
	}
	return copyMap(q), true
}

// SubscribeQueues returns a channel that receives Queue WatchEvents.
func (s *InMemoryStore) SubscribeQueues() <-chan metav1.WatchEvent {
	return s.queueBroadcaster.Subscribe()
}

// CancelQueueSubscription cancels a queue subscription.
func (s *InMemoryStore) CancelQueueSubscription(ch <-chan metav1.WatchEvent) {
	s.queueBroadcaster.CancelSubscription(ch)
}

// mustMarshal serialises v to JSON, panicking on error (only used for known-good structs).
func mustMarshal(v interface{}) []byte {
	b, err := json.Marshal(v)
	if err != nil {
		panic(err)
	}
	return b
}

// copyMap performs a shallow JSON round-trip copy of a map.
func copyMap(m map[string]interface{}) map[string]interface{} {
	b, _ := json.Marshal(m)
	var out map[string]interface{}
	_ = json.Unmarshal(b, &out)
	return out
}

// --- Volcano PodGroup operations ---

// CreatePodGroup stores a PodGroup and broadcasts an ADDED watch event.
func (s *InMemoryStore) CreatePodGroup(namespace, name string, raw map[string]interface{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	raw["metadata"].(map[string]interface{})["resourceVersion"] = rv
	key := namespace + "/" + name
	s.podGroups[key] = raw
	s.podGroupEventCh <- metav1.WatchEvent{
		Type:   "ADDED",
		Object: runtime.RawExtension{Raw: mustMarshal(raw)},
	}
}

// UpdatePodGroupRaw updates a stored PodGroup and broadcasts a MODIFIED watch event.
func (s *InMemoryStore) UpdatePodGroupRaw(namespace, name string, raw map[string]interface{}) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.resourceVersion++
	rv := fmt.Sprintf("%d", s.resourceVersion)
	meta, _ := raw["metadata"].(map[string]interface{})
	if meta == nil {
		meta = map[string]interface{}{}
		raw["metadata"] = meta
	}
	meta["resourceVersion"] = rv
	key := namespace + "/" + name
	s.podGroups[key] = raw
	s.podGroupEventCh <- metav1.WatchEvent{
		Type:   "MODIFIED",
		Object: runtime.RawExtension{Raw: mustMarshal(raw)},
	}
}

// DeletePodGroup removes a PodGroup and broadcasts a DELETED watch event.
func (s *InMemoryStore) DeletePodGroup(namespace, name string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	key := namespace + "/" + name
	pg, ok := s.podGroups[key]
	if !ok {
		return
	}
	delete(s.podGroups, key)
	s.podGroupEventCh <- metav1.WatchEvent{
		Type:   "DELETED",
		Object: runtime.RawExtension{Raw: mustMarshal(pg)},
	}
}

// GetPodGroups returns all PodGroups in the given namespace (or all if namespace is "").
func (s *InMemoryStore) GetPodGroups(namespace string) []map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()
	result := make([]map[string]interface{}, 0)
	for key, pg := range s.podGroups {
		if namespace == "" || key[:len(namespace)] == namespace {
			result = append(result, copyMap(pg))
		}
	}
	return result
}

// SubscribePodGroups returns a channel that receives PodGroup WatchEvents.
func (s *InMemoryStore) SubscribePodGroups() <-chan metav1.WatchEvent {
	return s.podGroupBroadcaster.Subscribe()
}

// CancelPodGroupSubscription cancels a PodGroup subscription.
func (s *InMemoryStore) CancelPodGroupSubscription(ch <-chan metav1.WatchEvent) {
	s.podGroupBroadcaster.CancelSubscription(ch)
}
