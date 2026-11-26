package store

import (
	"context"
	"fmt"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/rules"
)

// Roda contra um PostgreSQL de verdade quando SENTINEL_TEST_DB aponta para um. Sem ele, é pulado:
// as regras e a API têm testes próprios que não precisam de banco.
func open(t *testing.T) *Postgres {
	t.Helper()
	url := os.Getenv("SENTINEL_TEST_DB")
	if url == "" {
		t.Skip("SENTINEL_TEST_DB não definido")
	}
	p, err := Open(context.Background(), url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(p.Close)
	return p
}

func uuid(prefix int, n int) string {
	return fmt.Sprintf("%08d-0000-4000-8000-%012d", prefix, n)
}

func TestSaveIsIdempotentUnderConcurrency(t *testing.T) {
	p := open(t)
	ctx := context.Background()
	run := int(time.Now().UnixNano() % 1e8)
	request := rules.Request{TransferID: uuid(run, 1), AccountID: uuid(run, 2), RecipientAccountID: uuid(run, 3),
		AmountCents: 100, RequestedAt: time.Now(), AccountCreatedAt: time.Now().Add(-time.Hour)}

	var wg sync.WaitGroup
	results := make(chan rules.Result, 10)
	for i := range 10 {
		wg.Go(func() {
			decision := rules.Approved
			if i%2 == 1 {
				decision = rules.Denied
			}
			result, err := p.Save(ctx, request, rules.Result{TransferID: request.TransferID, Score: i, Decision: decision, Reasons: []rules.Reason{}})
			if err != nil {
				t.Error(err)
			}
			results <- result
		})
	}
	wg.Wait()
	close(results)
	var first *rules.Result
	for r := range results {
		if first == nil {
			first = &r
		} else if r.Score != first.Score || r.Decision != first.Decision {
			t.Fatalf("duas análises diferentes ficaram: %+v e %+v", *first, r)
		}
	}
}

func TestHistoryReadsOnlyThisAccount(t *testing.T) {
	p := open(t)
	ctx := context.Background()
	run := int(time.Now().UnixNano()%1e8) + 1
	at := time.Now()
	save := func(n int, account, recipient string, cents int64, decision rules.Decision, when time.Time) {
		r := rules.Request{TransferID: uuid(run, n), AccountID: account, RecipientAccountID: recipient, AmountCents: cents,
			RequestedAt: when, AccountCreatedAt: when.Add(-time.Hour)}
		if _, err := p.Save(ctx, r, rules.Result{TransferID: r.TransferID, Decision: decision, Reasons: []rules.Reason{}}); err != nil {
			t.Fatal(err)
		}
	}
	mine, other, friend := uuid(run, 100), uuid(run, 101), uuid(run, 102)
	save(1, mine, friend, 500, rules.Approved, at.Add(-time.Hour))
	save(2, mine, friend, 700, rules.Denied, at.Add(-2*time.Minute))
	save(3, other, friend, 9_999, rules.Approved, at.Add(-time.Minute))
	save(4, mine, friend, 300, rules.Approved, at.Add(-40*24*time.Hour))

	h, err := p.History(ctx, rules.Request{AccountID: mine, RecipientAccountID: friend, RequestedAt: at})
	if err != nil {
		t.Fatal(err)
	}
	if len(h.RecentAmounts) != 1 || h.RecentAmounts[0] != 500 || h.LastTenMinutes != 1 || !h.KnowsRecipient {
		t.Fatalf("histórico errado: %+v", h)
	}
}
