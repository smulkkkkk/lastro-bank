package api

import (
	"encoding/json"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/rules"
)

var secret = []byte("segredo-de-teste")

const account = "0b8c3e1a-7d42-4c1f-8e6a-5f9d2b4c7e31"

func server(store Store, now time.Time) http.Handler {
	s := NewServer(store, secret, slog.New(slog.NewTextHandler(io.Discard, nil)))
	s.now = func() time.Time { return now }
	return s.Routes()
}

func body(transfer, recipient string, cents int64, at time.Time) string {
	return fmt.Sprintf(`{"transferId":%q,"accountId":%q,"recipientAccountId":%q,"amountCents":%d,`+
		`"accountCreatedAt":"2026-01-01T00:00:00Z","requestedAt":%q}`,
		transfer, account, recipient, cents, at.UTC().Format(time.RFC3339))
}

func post(t *testing.T, h http.Handler, payload string, signedAt time.Time, signature string) *httptest.ResponseRecorder {
	t.Helper()
	timestamp := strconv.FormatInt(signedAt.Unix(), 10)
	if signature == "" {
		signature = Sign(secret, timestamp, []byte(payload))
	}
	request := httptest.NewRequest(http.MethodPost, "/v1/analyses", strings.NewReader(payload))
	request.Header.Set("X-Lastro-Timestamp", timestamp)
	request.Header.Set("X-Lastro-Signature", signature)
	recorder := httptest.NewRecorder()
	h.ServeHTTP(recorder, request)
	return recorder
}

func id(n int) string { return fmt.Sprintf("00000000-0000-4000-8000-%012d", n) }

func decode(t *testing.T, r *httptest.ResponseRecorder) rules.Result {
	t.Helper()
	var result rules.Result
	if err := json.Unmarshal(r.Body.Bytes(), &result); err != nil {
		t.Fatalf("%d %s", r.Code, r.Body)
	}
	return result
}

var now = time.Date(2026, 11, 25, 15, 0, 0, 0, time.UTC)

func TestSignedRequestIsAnalyzed(t *testing.T) {
	h := server(NewMemoryStore(), now)
	r := post(t, h, body(id(1), id(900), 100_00, now), now, "")
	if r.Code != http.StatusOK || decode(t, r).Decision != rules.Approved {
		t.Fatalf("%d %s", r.Code, r.Body)
	}
}

func TestBadSignaturesAreRefusedBeforeReadingTheBody(t *testing.T) {
	h := server(NewMemoryStore(), now)
	payload := body(id(1), id(900), 100_00, now)
	cases := map[string]*httptest.ResponseRecorder{
		"assinatura de outro corpo": post(t, h, payload, now, Sign(secret, strconv.FormatInt(now.Unix(), 10), []byte("{}"))),
		"outro segredo":             post(t, h, payload, now, Sign([]byte("errado"), strconv.FormatInt(now.Unix(), 10), []byte(payload))),
		"seis minutos atrás":        post(t, h, payload, now.Add(-6*time.Minute), ""),
		"seis minutos no futuro":    post(t, h, payload, now.Add(6*time.Minute), ""),
		"lixo no lugar":             post(t, h, payload, now, "zz"),
		"corpo inválido assinado":   nil,
	}
	cases["corpo inválido assinado"] = post(t, h, "nao e json", now.Add(time.Hour), "")
	for name, r := range cases {
		if r.Code != http.StatusUnauthorized {
			t.Errorf("%s: esperava 401, veio %d", name, r.Code)
		}
	}
	if r := post(t, h, payload, now.Add(-4*time.Minute), ""); r.Code != http.StatusOK {
		t.Errorf("quatro minutos ainda vale: %d", r.Code)
	}
}

func TestInvalidRequestsAreRefused(t *testing.T) {
	h := server(NewMemoryStore(), now)
	for name, payload := range map[string]string{
		"campo a mais":      strings.Replace(body(id(1), id(900), 100, now), "{", `{"extra":1,`, 1),
		"valor zero":        body(id(1), id(900), 0, now),
		"para si mesmo":     body(id(1), account, 100, now),
		"id que não é uuid": body("abc", id(900), 100, now),
		"conta do futuro":   strings.Replace(body(id(1), id(900), 100, now), "2026-01-01", "2027-01-01", 1),
		"não é json":        "{",
	} {
		if r := post(t, h, payload, now, ""); r.Code != http.StatusBadRequest {
			t.Errorf("%s: esperava 400, veio %d %s", name, r.Code, r.Body)
		}
	}
}

func TestSameTransferAlwaysGetsTheSameAnswer(t *testing.T) {
	h := server(NewMemoryStore(), now)
	first := decode(t, post(t, h, body(id(1), id(900), 900_00, now), now, ""))
	// Pedido repetido com outro valor não muda a análise já feita.
	again := decode(t, post(t, h, body(id(1), id(900), 1_00, now), now, ""))
	if first.Score != again.Score || first.Decision != again.Decision {
		t.Fatalf("mudou: %+v / %+v", first, again)
	}
}

func TestSimultaneousRetriesStoreOneAnalysis(t *testing.T) {
	store := NewMemoryStore()
	h := server(store, now)
	var wg sync.WaitGroup
	decisions := make(chan rules.Decision, 20)
	for range 20 {
		wg.Go(func() {
			decisions <- decode(t, post(t, h, body(id(7), id(900), 600_00, now), now, "")).Decision
		})
	}
	wg.Wait()
	close(decisions)
	for d := range decisions {
		if d != rules.Approved {
			t.Fatalf("retentativas deram respostas diferentes: %s", d)
		}
	}
	if len(store.analyses) != 1 {
		t.Fatalf("esperava 1 análise, tem %d", len(store.analyses))
	}
}

func TestHistoryFeedsTheRules(t *testing.T) {
	h := server(NewMemoryStore(), now)
	// Primeiro Pix de R$ 600 para alguém novo: só a regra do destinatário novo (25), aprovado.
	if d := decode(t, post(t, h, body(id(1), id(900), 600_00, now), now, "")); d.Score != 25 {
		t.Fatalf("primeiro: %+v", d)
	}
	// Mesmo destinatário, já conhecido: nada.
	if d := decode(t, post(t, h, body(id(2), id(900), 600_00, now.Add(time.Minute)), now, "")); d.Score != 0 {
		t.Fatalf("conhecido: %+v", d)
	}
	// Média de 600; 3.001 é mais de 5x e para alguém novo: 35 + 25 = 60, revisão.
	if d := decode(t, post(t, h, body(id(3), id(901), 3_001_00, now.Add(2*time.Minute)), now, "")); d.Decision != rules.Review {
		t.Fatalf("acima da média: %+v", d)
	}
	// Mais três rápidos: o 6º em 10 minutos ganha a regra da rajada.
	for i := 4; i <= 6; i++ {
		post(t, h, body(id(i), id(900), 10_00, now.Add(time.Duration(i)*time.Minute)), now, "")
	}
	if d := decode(t, post(t, h, body(id(10), id(900), 10_00, now.Add(7*time.Minute)), now, "")); d.Score != 40 {
		t.Fatalf("rajada: %+v", d)
	}
}

func TestHealth(t *testing.T) {
	r := httptest.NewRecorder()
	server(NewMemoryStore(), now).ServeHTTP(r, httptest.NewRequest(http.MethodGet, "/healthz", nil))
	if r.Code != http.StatusOK {
		t.Fatal(r.Code)
	}
}

// O exemplo do contrato, assinado como o Java assina, passa pelo servidor inteiro.
func TestContractRequestPassesTheServer(t *testing.T) {
	payload, err := os.ReadFile("../../../contracts/analysis-request.json")
	if err != nil {
		t.Fatal(err)
	}
	at := time.Date(2026, 11, 25, 23, 30, 0, 0, time.UTC)
	r := post(t, server(NewMemoryStore(), at), string(payload), at, "")
	if r.Code != http.StatusOK || decode(t, r).Decision != rules.Review {
		t.Fatalf("%d %s", r.Code, r.Body)
	}
}

// Mesmo vetor do teste Java (SentinelContractTest): os dois lados assinam igual.
func TestSignatureVectorSharedWithJava(t *testing.T) {
	got := Sign([]byte("segredo-de-teste-do-lastro"), "1700000000", []byte("{}"))
	if got != "777a51ebb0384f86100ebda4307ff3ed9f89ca41acde72ce416edfe93f19d373" {
		t.Fatalf("assinatura diferente da do Java: %s", got)
	}
}
