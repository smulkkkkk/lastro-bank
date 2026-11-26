// Sentinel: análise de risco das transferências do Lastro.
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/api"
	"github.com/smulkkkkk/lastro-bank/sentinel/internal/auth"
	"github.com/smulkkkkk/lastro-bank/sentinel/internal/store"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(log); err != nil {
		log.Error("sentinel parou", "error", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	secret := os.Getenv("SENTINEL_SECRET")
	if len(secret) < 16 {
		return errors.New("defina SENTINEL_SECRET com pelo menos 16 caracteres, o mesmo do core-bank")
	}
	url := env("SENTINEL_DB_URL", "postgres://lastro_sentinel@localhost:5433/lastro")
	addr := env("SENTINEL_ADDR", "127.0.0.1:8081")
	jwks := env("SENTINEL_JWKS_URL", "http://127.0.0.1:8080/.well-known/jwks.json")

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	db, err := store.Open(ctx, url)
	if err != nil {
		return err
	}
	defer db.Close()

	server := &http.Server{
		Addr: addr,
		Handler: api.NewServer(db, []byte(secret), log).
			EnableNotifications(db, auth.NewVerifier(jwks, "lastro-core", "lastro")).Routes(),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		// Sem WriteTimeout geral: o stream de avisos fica aberto e põe prazo em cada escrita.
		IdleTimeout: 2 * time.Minute,
	}
	failed := make(chan error, 1)
	go func() {
		log.Info("sentinel no ar", "addr", addr)
		if err := server.ListenAndServe(); !errors.Is(err, http.ErrServerClosed) {
			failed <- err
		}
	}()

	select {
	case err := <-failed:
		return err
	case <-ctx.Done():
	}
	// Termina as análises em andamento antes de sair.
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return server.Shutdown(shutdown)
}

func env(name, fallback string) string {
	if value := os.Getenv(name); value != "" {
		return value
	}
	return fallback
}
