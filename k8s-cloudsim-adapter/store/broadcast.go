package store

import "context"

// BroadcastServer is a generic fan-out channel multiplexer.
// It reads from a source channel and fans out to all subscriber channels.
type BroadcastServer[T any] struct {
	source         <-chan T
	listeners      []chan T
	addListener    chan chan T
	removeListener chan (<-chan T)
}

// NewBroadcastServer creates a new BroadcastServer and starts its goroutine.
// When the source channel is closed, the goroutine terminates and all subscriber channels are closed.
func NewBroadcastServer[T any](ctx context.Context, source <-chan T) *BroadcastServer[T] {
	s := &BroadcastServer[T]{
		source:         source,
		listeners:      make([]chan T, 0),
		addListener:    make(chan chan T),
		removeListener: make(chan (<-chan T)),
	}
	go s.serve(ctx)
	return s
}

// Subscribe returns a new channel that will receive all future events.
func (s *BroadcastServer[T]) Subscribe() <-chan T {
	newListener := make(chan T, 500)
	s.addListener <- newListener
	return newListener
}

// CancelSubscription removes the given channel from the subscriber list and closes it.
func (s *BroadcastServer[T]) CancelSubscription(ch <-chan T) {
	s.removeListener <- ch
}

func (s *BroadcastServer[T]) serve(ctx context.Context) {
	defer func() {
		for _, listener := range s.listeners {
			if listener != nil {
				close(listener)
			}
		}
	}()

	for {
		select {
		case <-ctx.Done():
			return
		case newListener := <-s.addListener:
			s.listeners = append(s.listeners, newListener)
		case listenerToRemove := <-s.removeListener:
			for i, ch := range s.listeners {
				if ch == listenerToRemove {
					s.listeners[i] = s.listeners[len(s.listeners)-1]
					s.listeners = s.listeners[:len(s.listeners)-1]
					close(ch)
					break
				}
			}
		case val, ok := <-s.source:
			if !ok {
				return
			}
			for _, listener := range s.listeners {
				if listener != nil {
					select {
					case listener <- val:
					case <-ctx.Done():
						return
					}
				}
			}
		}
	}
}
