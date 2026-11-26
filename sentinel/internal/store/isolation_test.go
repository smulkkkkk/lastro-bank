package store

import (
	"context"
	"errors"
	"testing"

	"github.com/jackc/pgx/v5/pgconn"
)

// O sentinel não pode ler nem escrever no razão do core-bank, nem por engano.
func TestCannotTouchTheCoreSchema(t *testing.T) {
	p := open(t)
	ctx := context.Background()
	for _, sql := range []string{
		"SELECT count(*) FROM core.account",
		"SELECT count(*) FROM core.ledger_entry",
		"UPDATE core.account SET held_cents = 0",
	} {
		_, err := p.pool.Exec(ctx, sql)
		// Pelo código e não pelo texto: a mensagem muda com o idioma do servidor.
		var pgErr *pgconn.PgError
		if !errors.As(err, &pgErr) || pgErr.Code != "42501" {
			t.Errorf("%s: esperava permissão negada, veio %v", sql, err)
		}
	}
}
