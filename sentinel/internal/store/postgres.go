// Package store guarda as análises no schema sentinel do PostgreSQL.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/rules"
)

// O sentinel só enxerga o próprio schema; o core-bank é outro usuário do banco, sem acesso aqui.
const schema = `
CREATE TABLE IF NOT EXISTS sentinel.analysis (
    transfer_id  uuid        PRIMARY KEY,
    account_id   uuid        NOT NULL,
    recipient_id uuid        NOT NULL,
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),
    requested_at timestamptz NOT NULL,
    score        smallint    NOT NULL CHECK (score BETWEEN 0 AND 100),
    decision     text        NOT NULL CHECK (decision IN ('APPROVED', 'REVIEW', 'DENIED')),
    reasons      jsonb       NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS analysis_account_idx ON sentinel.analysis (account_id, requested_at);
`

type Postgres struct {
	pool *pgxpool.Pool
}

func Open(ctx context.Context, url string) (*Postgres, error) {
	config, err := pgxpool.ParseConfig(url)
	if err != nil {
		return nil, fmt.Errorf("endereço do banco: %w", err)
	}
	config.MaxConns = 4
	pool, err := pgxpool.NewWithConfig(ctx, config)
	if err != nil {
		return nil, err
	}
	if _, err := pool.Exec(ctx, schema+notificationSchema); err != nil {
		pool.Close()
		return nil, fmt.Errorf("criar tabelas: %w", err)
	}
	return &Postgres{pool: pool}, nil
}

func (p *Postgres) Close() { p.pool.Close() }

func (p *Postgres) Ping(ctx context.Context) error { return p.pool.Ping(ctx) }

func (p *Postgres) Find(ctx context.Context, transferID string) (rules.Result, bool, error) {
	var result rules.Result
	var reasons []byte
	err := p.pool.QueryRow(ctx,
		`SELECT transfer_id::text, score, decision, reasons FROM sentinel.analysis WHERE transfer_id = $1`, transferID).
		Scan(&result.TransferID, &result.Score, &result.Decision, &reasons)
	if errors.Is(err, pgx.ErrNoRows) {
		return rules.Result{}, false, nil
	}
	if err != nil {
		return rules.Result{}, false, err
	}
	return result, true, json.Unmarshal(reasons, &result.Reasons)
}

// History usa só o que o próprio sentinel analisou: nunca lê o razão do core-bank.
func (p *Postgres) History(ctx context.Context, r rules.Request) (rules.History, error) {
	var h rules.History
	err := p.pool.QueryRow(ctx, `
		SELECT coalesce(array_agg(amount_cents) FILTER (WHERE decision = 'APPROVED'
		                AND requested_at > $2::timestamptz - interval '30 days'), '{}'),
		       count(*) FILTER (WHERE requested_at > $2::timestamptz - interval '10 minutes'),
		       coalesce(bool_or(recipient_id = $3 AND decision = 'APPROVED'), false)
		  FROM sentinel.analysis
		 WHERE account_id = $1 AND requested_at <= $2`,
		r.AccountID, r.RequestedAt, r.RecipientAccountID).
		Scan(&h.RecentAmounts, &h.LastTenMinutes, &h.KnowsRecipient)
	return h, err
}

// Save usa ON CONFLICT: duas retentativas simultâneas gravam uma análise e devolvem a mesma.
func (p *Postgres) Save(ctx context.Context, r rules.Request, result rules.Result) (rules.Result, error) {
	reasons, err := json.Marshal(result.Reasons)
	if err != nil {
		return rules.Result{}, err
	}
	tag, err := p.pool.Exec(ctx, `
		INSERT INTO sentinel.analysis (transfer_id, account_id, recipient_id, amount_cents, requested_at, score, decision, reasons)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
		ON CONFLICT (transfer_id) DO NOTHING`,
		r.TransferID, r.AccountID, r.RecipientAccountID, r.AmountCents, r.RequestedAt, result.Score, result.Decision, reasons)
	if err != nil {
		return rules.Result{}, err
	}
	if tag.RowsAffected() == 1 {
		return result, nil
	}
	stored, _, err := p.Find(ctx, r.TransferID)
	return stored, err
}
