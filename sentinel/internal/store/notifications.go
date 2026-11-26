package store

import (
	"context"
	"errors"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/notify"
)

const notificationSchema = `
CREATE TABLE IF NOT EXISTS sentinel.notification (
    seq        bigserial   PRIMARY KEY,
    id         uuid        NOT NULL UNIQUE,
    user_id    uuid        NOT NULL,
    kind       text        NOT NULL,
    title      text        NOT NULL CHECK (length(title) BETWEEN 1 AND 80),
    body       text        NOT NULL CHECK (length(body) <= 280),
    link       text,
    created_at timestamptz NOT NULL,
    read_at    timestamptz
);
CREATE INDEX IF NOT EXISTS notification_user_idx ON sentinel.notification (user_id, seq);
`

const columns = `seq, id::text, kind, title, body, coalesce(link, ''), created_at, read_at`

func scan(row pgx.Row) (notify.Notification, error) {
	var n notify.Notification
	err := row.Scan(&n.Seq, &n.ID, &n.Kind, &n.Title, &n.Body, &n.Link, &n.CreatedAt, &n.ReadAt)
	return n, err
}

// SaveEvent é idempotente pelo id do evento: o core-bank pode mandar de novo depois de uma falha.
func (p *Postgres) SaveEvent(ctx context.Context, e notify.Event) (notify.Notification, bool, error) {
	var link *string
	if e.Link != "" {
		link = &e.Link
	}
	n, err := scan(p.pool.QueryRow(ctx, `
		INSERT INTO sentinel.notification (id, user_id, kind, title, body, link, created_at)
		VALUES ($1, $2, $3, $4, $5, $6, $7)
		ON CONFLICT (id) DO NOTHING
		RETURNING `+columns, e.ID, e.UserID, e.Kind, e.Title, e.Body, link, e.CreatedAt))
	if err == nil {
		return n, true, nil
	}
	if !errors.Is(err, pgx.ErrNoRows) {
		return notify.Notification{}, false, err
	}
	n, err = scan(p.pool.QueryRow(ctx, `SELECT `+columns+` FROM sentinel.notification WHERE id = $1`, e.ID))
	return n, false, err
}

func (p *Postgres) Since(ctx context.Context, userID string, after int64, limit int) ([]notify.Notification, error) {
	rows, err := p.pool.Query(ctx, `SELECT `+columns+` FROM sentinel.notification
		WHERE user_id = $1 AND seq > $2 ORDER BY seq LIMIT $3`, userID, after, limit)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, func(r pgx.CollectableRow) (notify.Notification, error) { return scan(r) })
}

func (p *Postgres) Recent(ctx context.Context, userID string, limit int) ([]notify.Notification, int, error) {
	rows, err := p.pool.Query(ctx, `SELECT `+columns+` FROM sentinel.notification
		WHERE user_id = $1 ORDER BY seq DESC LIMIT $2`, userID, limit)
	if err != nil {
		return nil, 0, err
	}
	items, err := pgx.CollectRows(rows, func(r pgx.CollectableRow) (notify.Notification, error) { return scan(r) })
	if err != nil {
		return nil, 0, err
	}
	var unread int
	err = p.pool.QueryRow(ctx, `SELECT count(*) FROM sentinel.notification WHERE user_id = $1 AND read_at IS NULL`,
		userID).Scan(&unread)
	return items, unread, err
}

func (p *Postgres) MarkRead(ctx context.Context, userID string, upTo int64, at time.Time) error {
	_, err := p.pool.Exec(ctx, `UPDATE sentinel.notification SET read_at = $3
		WHERE user_id = $1 AND seq <= $2 AND read_at IS NULL`, userID, upTo, at)
	return err
}
