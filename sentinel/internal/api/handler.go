// Package api expõe o sentinel por HTTP.
package api

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"regexp"
	"time"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/notify"
	"github.com/smulkkkkk/lastro-bank/sentinel/internal/rules"
)

// Store guarda as análises e responde o histórico que as regras precisam.
type Store interface {
	// Find devolve a análise já feita para a transferência, se houver.
	Find(ctx context.Context, transferID string) (rules.Result, bool, error)
	History(ctx context.Context, request rules.Request) (rules.History, error)
	// Save grava a análise. Se outra chamada gravou primeiro, devolve a que ficou.
	Save(ctx context.Context, request rules.Request, result rules.Result) (rules.Result, error)
	Ping(ctx context.Context) error
}

const maxBody = 16 << 10

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Server struct {
	store  Store
	secret []byte
	now    func() time.Time
	log    *slog.Logger

	notes  Notifications
	tokens TokenVerifier
	hub    *notify.Hub
	ping   time.Duration
}

func NewServer(store Store, secret []byte, log *slog.Logger) *Server {
	return &Server{store: store, secret: secret, now: time.Now, log: log}
}

func (s *Server) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/analyses", s.analyze)
	mux.HandleFunc("GET /healthz", s.health)
	s.liveRoutes(mux)
	return mux
}

// readSigned lê o corpo e confere a assinatura antes de qualquer leitura do conteúdo.
func (s *Server) readSigned(w http.ResponseWriter, r *http.Request) ([]byte, bool) {
	body, err := io.ReadAll(io.LimitReader(r.Body, maxBody+1))
	if err != nil || len(body) > maxBody {
		problem(w, http.StatusRequestEntityTooLarge, "request.too_large", "Corpo grande demais.")
		return nil, false
	}
	if !Verify(s.secret, r.Header.Get("X-Lastro-Timestamp"), r.Header.Get("X-Lastro-Signature"), body, s.now()) {
		s.log.Warn("assinatura recusada", "trace", r.Header.Get("X-Trace-Id"), "remote", r.RemoteAddr)
		problem(w, http.StatusUnauthorized, "signature.invalid", "Assinatura inválida ou expirada.")
		return nil, false
	}
	return body, true
}

func (s *Server) analyze(w http.ResponseWriter, r *http.Request) {
	trace := r.Header.Get("X-Trace-Id")
	body, ok := s.readSigned(w, r)
	if !ok {
		return
	}

	var request rules.Request
	decoder := json.NewDecoder(bytesReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&request); err != nil || !valid(request) {
		problem(w, http.StatusBadRequest, "request.invalid", "Pedido de análise inválido.")
		return
	}

	ctx := r.Context()
	if done, found, err := s.store.Find(ctx, request.TransferID); err != nil {
		s.fail(w, trace, err)
		return
	} else if found {
		write(w, http.StatusOK, done)
		return
	}
	history, err := s.store.History(ctx, request)
	if err != nil {
		s.fail(w, trace, err)
		return
	}
	result, err := s.store.Save(ctx, request, rules.Evaluate(request, history))
	if err != nil {
		s.fail(w, trace, err)
		return
	}
	s.log.Info("análise", "trace", trace, "transfer", request.TransferID, "score", result.Score, "decision", result.Decision)
	write(w, http.StatusOK, result)
}

func valid(r rules.Request) bool {
	return uuid.MatchString(r.TransferID) && uuid.MatchString(r.AccountID) && uuid.MatchString(r.RecipientAccountID) &&
		r.AccountID != r.RecipientAccountID && r.AmountCents > 0 && !r.AccountCreatedAt.IsZero() &&
		!r.RequestedAt.IsZero() && !r.AccountCreatedAt.After(r.RequestedAt)
}

func (s *Server) health(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.store.Ping(ctx); err != nil {
		problem(w, http.StatusServiceUnavailable, "store.down", "Banco indisponível.")
		return
	}
	write(w, http.StatusOK, map[string]string{"status": "UP"})
}

func (s *Server) fail(w http.ResponseWriter, trace string, err error) {
	if errors.Is(err, context.Canceled) {
		return
	}
	s.log.Error("falha na análise", "trace", trace, "error", err)
	problem(w, http.StatusInternalServerError, "internal.error", "Erro interno.")
}

func write(w http.ResponseWriter, status int, body any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(body)
}

func problem(w http.ResponseWriter, status int, code, detail string) {
	w.Header().Set("Content-Type", "application/problem+json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]any{"status": status, "code": code, "detail": detail})
}
