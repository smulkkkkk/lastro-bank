// Package rules calcula a nota de risco de uma transferência.
//
// Cada regra é uma função pura: recebe o pedido e o histórico e devolve pontos e motivo. A nota é a
// soma, limitada a 100. Nada aqui fala com rede ou banco, então tudo é testável sem infraestrutura.
package rules

import (
	"time"
)

// Request é o que o core-bank manda analisar.
type Request struct {
	TransferID         string    `json:"transferId"`
	AccountID          string    `json:"accountId"`
	RecipientAccountID string    `json:"recipientAccountId"`
	AmountCents        int64     `json:"amountCents"`
	AccountCreatedAt   time.Time `json:"accountCreatedAt"`
	RequestedAt        time.Time `json:"requestedAt"`
}

// History é o que o sentinel já sabe da conta, a partir das análises que ele mesmo fez.
type History struct {
	// Valores aprovados nos últimos 30 dias.
	RecentAmounts []int64
	// Transferências pedidas nos últimos 10 minutos, aprovadas ou não.
	LastTenMinutes int
	// Se esta conta já mandou dinheiro aprovado para o destinatário.
	KnowsRecipient bool
}

// Reason explica cada ponto da nota. O cliente não vê; o analista vê.
type Reason struct {
	Code   string `json:"code"`
	Points int    `json:"points"`
	Text   string `json:"text"`
}

type Decision string

const (
	Approved Decision = "APPROVED"
	Review   Decision = "REVIEW"
	Denied   Decision = "DENIED"
)

// Result é a resposta para o core-bank.
type Result struct {
	TransferID string   `json:"transferId"`
	Score      int      `json:"score"`
	Decision   Decision `json:"decision"`
	Reasons    []Reason `json:"reasons"`
}

var saoPaulo = mustLoad("America/Sao_Paulo")

func mustLoad(name string) *time.Location {
	location, err := time.LoadLocation(name)
	if err != nil {
		// Sem a base de fusos do sistema, usa o horário fixo de Brasília (sem horário de verão desde 2019).
		return time.FixedZone("BRT", -3*60*60)
	}
	return location
}

type rule func(Request, History) (int, string, string)

var all = []rule{
	aboveAverage,
	newRecipient,
	burst,
	lateNight,
	newAccount,
}

// Evaluate aplica todas as regras. A ordem dos motivos é a ordem das regras, para a resposta ser estável.
func Evaluate(request Request, history History) Result {
	result := Result{TransferID: request.TransferID, Reasons: []Reason{}}
	for _, check := range all {
		points, code, text := check(request, history)
		if points > 0 {
			result.Score += points
			result.Reasons = append(result.Reasons, Reason{Code: code, Points: points, Text: text})
		}
	}
	result.Score = min(result.Score, 100)
	result.Decision = decide(result.Score)
	return result
}

func decide(score int) Decision {
	switch {
	case score >= 70:
		return Denied
	case score >= 40:
		return Review
	default:
		return Approved
	}
}

// Sem histórico não há média: a regra simplesmente não pontua.
func aboveAverage(request Request, history History) (int, string, string) {
	if len(history.RecentAmounts) == 0 {
		return 0, "", ""
	}
	var sum int64
	for _, amount := range history.RecentAmounts {
		sum += amount
	}
	average := sum / int64(len(history.RecentAmounts))
	if request.AmountCents > 5*average {
		return 35, "ABOVE_AVERAGE", "Valor acima de 5 vezes a média dos últimos 30 dias"
	}
	return 0, "", ""
}

func newRecipient(request Request, history History) (int, string, string) {
	if !history.KnowsRecipient && request.AmountCents > 500_00 {
		return 25, "NEW_RECIPIENT", "Primeira transferência para este destinatário, acima de R$ 500,00"
	}
	return 0, "", ""
}

// LastTenMinutes não conta a transferência em análise: a 6ª em 10 minutos pontua.
func burst(_ Request, history History) (int, string, string) {
	if history.LastTenMinutes >= 5 {
		return 40, "BURST", "Mais de 5 transferências em 10 minutos"
	}
	return 0, "", ""
}

func lateNight(request Request, _ History) (int, string, string) {
	hour := request.RequestedAt.In(saoPaulo).Hour()
	if (hour >= 23 || hour < 5) && request.AmountCents > 1_000_00 {
		return 20, "LATE_NIGHT", "Entre 23h e 5h, acima de R$ 1.000,00"
	}
	return 0, "", ""
}

func newAccount(request Request, _ History) (int, string, string) {
	if request.RequestedAt.Sub(request.AccountCreatedAt) < 24*time.Hour && request.AmountCents > 800_00 {
		return 25, "NEW_ACCOUNT", "Conta com menos de 24 horas, acima de R$ 800,00"
	}
	return 0, "", ""
}
