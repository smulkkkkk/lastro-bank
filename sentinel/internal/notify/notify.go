// Package notify guarda os avisos de cada cliente e os entrega a quem está conectado.
package notify

import (
	"context"
	"errors"
	"sync"
	"time"
)

// Kinds são os avisos que o core-bank pode mandar. Qualquer outro é recusado.
var Kinds = map[string]bool{
	"PIX_RECEIVED":  true,
	"PIX_SENT":      true,
	"PIX_REVIEW":    true,
	"PIX_DENIED":    true,
	"PIX_CANCELLED": true,
	"SUPPORT":       true,
}

// Event é o que o core-bank manda; o formato está em contracts/notification-event.json.
type Event struct {
	ID        string    `json:"id"`
	UserID    string    `json:"userId"`
	Kind      string    `json:"kind"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
	Link      string    `json:"link,omitempty"`
	CreatedAt time.Time `json:"createdAt"`
}

// Notification é o aviso guardado. Seq cresce sempre e serve de Last-Event-ID na reconexão.
type Notification struct {
	Seq       int64      `json:"seq"`
	ID        string     `json:"id"`
	Kind      string     `json:"kind"`
	Title     string     `json:"title"`
	Body      string     `json:"body"`
	Link      string     `json:"link,omitempty"`
	CreatedAt time.Time  `json:"createdAt"`
	ReadAt    *time.Time `json:"readAt"`
	userID    string
}

// MaxPerUser limita conexões abertas por cliente: abas demais não seguram memória do servidor.
const MaxPerUser = 5

var ErrTooMany = errors.New("conexões demais para este cliente")

// Hub entrega avisos novos a quem está conectado. Em mais de uma instância, quem perder o aviso ao
// vivo recebe na reconexão, pelo Last-Event-ID.
type Hub struct {
	mu   sync.Mutex
	subs map[string]map[chan Notification]struct{}
}

func NewHub() *Hub { return &Hub{subs: map[string]map[chan Notification]struct{}{}} }

func (h *Hub) Subscribe(userID string) (<-chan Notification, func(), error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if len(h.subs[userID]) >= MaxPerUser {
		return nil, nil, ErrTooMany
	}
	ch := make(chan Notification, 16)
	if h.subs[userID] == nil {
		h.subs[userID] = map[chan Notification]struct{}{}
	}
	h.subs[userID][ch] = struct{}{}
	cancel := func() {
		h.mu.Lock()
		defer h.mu.Unlock()
		delete(h.subs[userID], ch)
		if len(h.subs[userID]) == 0 {
			delete(h.subs, userID)
		}
	}
	return ch, cancel, nil
}

// Publish nunca trava: um cliente lento perde o aviso ao vivo e o recebe ao reconectar.
func (h *Hub) Publish(userID string, n Notification) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs[userID] {
		select {
		case ch <- n:
		default:
		}
	}
}

// Memory guarda avisos em memória, para os testes.
type Memory struct {
	mu    sync.Mutex
	seq   int64
	items []Notification
}

func NewMemory() *Memory { return &Memory{} }

func (m *Memory) SaveEvent(_ context.Context, e Event) (Notification, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, n := range m.items {
		if n.ID == e.ID {
			return n, false, nil
		}
	}
	m.seq++
	n := Notification{Seq: m.seq, ID: e.ID, Kind: e.Kind, Title: e.Title, Body: e.Body, Link: e.Link,
		CreatedAt: e.CreatedAt, userID: e.UserID}
	m.items = append(m.items, n)
	return n, true, nil
}

func (m *Memory) Since(_ context.Context, userID string, after int64, limit int) ([]Notification, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Notification
	for _, n := range m.items {
		if n.userID == userID && n.Seq > after && len(out) < limit {
			out = append(out, n)
		}
	}
	return out, nil
}

func (m *Memory) Recent(_ context.Context, userID string, limit int) ([]Notification, int, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Notification
	unread := 0
	for i := len(m.items) - 1; i >= 0; i-- {
		n := m.items[i]
		if n.userID != userID {
			continue
		}
		if n.ReadAt == nil {
			unread++
		}
		if len(out) < limit {
			out = append(out, n)
		}
	}
	return out, unread, nil
}

func (m *Memory) MarkRead(_ context.Context, userID string, upTo int64, at time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for i := range m.items {
		if m.items[i].userID == userID && m.items[i].Seq <= upTo && m.items[i].ReadAt == nil {
			m.items[i].ReadAt = &at
		}
	}
	return nil
}
