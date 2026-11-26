package api

import (
	"bytes"
	"context"
	"io"
	"sync"
	"time"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/rules"
)

func bytesReader(body []byte) io.Reader { return bytes.NewReader(body) }

// MemoryStore guarda tudo em memória. Serve para testes; em produção vale o Postgres.
type MemoryStore struct {
	mu       sync.Mutex
	analyses map[string]stored
}

type stored struct {
	request rules.Request
	result  rules.Result
}

func NewMemoryStore() *MemoryStore {
	return &MemoryStore{analyses: map[string]stored{}}
}

func (m *MemoryStore) Find(_ context.Context, id string) (rules.Result, bool, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	s, ok := m.analyses[id]
	return s.result, ok, nil
}

func (m *MemoryStore) History(_ context.Context, r rules.Request) (rules.History, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	var h rules.History
	for _, s := range m.analyses {
		if s.request.AccountID != r.AccountID {
			continue
		}
		age := r.RequestedAt.Sub(s.request.RequestedAt)
		if age >= 0 && age < 10*time.Minute {
			h.LastTenMinutes++
		}
		if s.result.Decision == rules.Approved {
			if age >= 0 && age < 30*24*time.Hour {
				h.RecentAmounts = append(h.RecentAmounts, s.request.AmountCents)
			}
			if s.request.RecipientAccountID == r.RecipientAccountID {
				h.KnowsRecipient = true
			}
		}
	}
	return h, nil
}

func (m *MemoryStore) Save(_ context.Context, r rules.Request, result rules.Result) (rules.Result, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.analyses[r.TransferID]; ok {
		return s.result, nil
	}
	m.analyses[r.TransferID] = stored{request: r, result: result}
	return result, nil
}

func (m *MemoryStore) Ping(context.Context) error { return nil }
