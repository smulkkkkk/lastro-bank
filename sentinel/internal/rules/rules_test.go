package rules

import (
	"encoding/json"
	"os"
	"testing"
	"time"
)

var (
	noon  = time.Date(2026, 11, 25, 15, 0, 0, 0, time.UTC) // 12h em São Paulo
	old   = noon.Add(-30 * 24 * time.Hour)
	known = History{KnowsRecipient: true}
)

func request(cents int64, at time.Time, created time.Time) Request {
	return Request{TransferID: "t", AmountCents: cents, RequestedAt: at, AccountCreatedAt: created}
}

func TestEachRuleAlone(t *testing.T) {
	cases := []struct {
		name    string
		request Request
		history History
		code    string
		points  int
	}{
		{"acima da média", request(600_00, noon, old), History{KnowsRecipient: true, RecentAmounts: []int64{100_00, 100_00}}, "ABOVE_AVERAGE", 35},
		{"destinatário novo", request(500_01, noon, old), History{}, "NEW_RECIPIENT", 25},
		{"rajada", request(10_00, noon, old), History{KnowsRecipient: true, LastTenMinutes: 5}, "BURST", 40},
		{"madrugada", request(1_000_01, time.Date(2026, 11, 26, 2, 30, 0, 0, time.UTC), old), known, "LATE_NIGHT", 20},
		{"conta nova", request(800_01, noon, noon.Add(-23*time.Hour)), known, "NEW_ACCOUNT", 25},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			result := Evaluate(c.request, c.history)
			if result.Score != c.points || len(result.Reasons) != 1 || result.Reasons[0].Code != c.code {
				t.Fatalf("esperava só %s com %d pontos, veio %+v", c.code, c.points, result)
			}
		})
	}
}

func TestLimitsAreExclusive(t *testing.T) {
	quiet := []struct {
		name    string
		request Request
		history History
	}{
		{"exatamente 5x a média", request(500_00, noon, old), History{KnowsRecipient: true, RecentAmounts: []int64{100_00}}},
		{"exatamente R$ 500 para alguém novo", request(500_00, noon, old), History{}},
		{"4 nos últimos 10 minutos", request(10_00, noon, old), History{KnowsRecipient: true, LastTenMinutes: 4}},
		{"22h59 em São Paulo", request(5_000_00, time.Date(2026, 11, 26, 1, 59, 0, 0, time.UTC), old), known},
		{"5h00 em São Paulo", request(5_000_00, time.Date(2026, 11, 26, 8, 0, 0, 0, time.UTC), old), known},
		{"conta com 24 horas", request(5_000_00, noon, noon.Add(-24*time.Hour)), known},
		{"sem histórico não há média", request(4_000_00, noon, old), known},
	}
	for _, c := range quiet {
		t.Run(c.name, func(t *testing.T) {
			if result := Evaluate(c.request, c.history); result.Score != 0 {
				t.Fatalf("não devia pontuar: %+v", result)
			}
		})
	}
}

func TestDecisionBands(t *testing.T) {
	for score, want := range map[int]Decision{0: Approved, 39: Approved, 40: Review, 69: Review, 70: Denied, 100: Denied} {
		if got := decide(score); got != want {
			t.Errorf("nota %d: esperava %s, veio %s", score, want, got)
		}
	}
}

func TestScoreIsCappedAt100(t *testing.T) {
	late := time.Date(2026, 11, 26, 3, 0, 0, 0, time.UTC)
	result := Evaluate(request(10_000_00, late, late.Add(-time.Hour)), History{RecentAmounts: []int64{1_00}, LastTenMinutes: 9})
	if result.Score != 100 || result.Decision != Denied || len(result.Reasons) != 5 {
		t.Fatalf("todas as regras juntas: %+v", result)
	}
}

// O exemplo do contrato tem que produzir exatamente a resposta do contrato.
func TestContractExample(t *testing.T) {
	var req Request
	read(t, "../../../contracts/analysis-request.json", &req)
	var want Result
	read(t, "../../../contracts/analysis-response.json", &want)

	got := Evaluate(req, History{})
	gotJSON, _ := json.Marshal(got)
	wantJSON, _ := json.Marshal(want)
	if string(gotJSON) != string(wantJSON) {
		t.Fatalf("contrato divergiu\n veio: %s\nesperado: %s", gotJSON, wantJSON)
	}
}

func read(t *testing.T, path string, into any) {
	t.Helper()
	body, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	decoder := json.NewDecoder(bytesReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(into); err != nil {
		t.Fatalf("%s: %v", path, err)
	}
}
