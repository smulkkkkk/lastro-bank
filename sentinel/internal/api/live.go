package api

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/auth"
	"github.com/smulkkkkk/lastro-bank/sentinel/internal/notify"
)

// Notifications guarda os avisos de cada cliente.
type Notifications interface {
	// SaveEvent devolve false quando o evento já tinha chegado antes.
	SaveEvent(ctx context.Context, e notify.Event) (notify.Notification, bool, error)
	Since(ctx context.Context, userID string, after int64, limit int) ([]notify.Notification, error)
	Recent(ctx context.Context, userID string, limit int) ([]notify.Notification, int, error)
	MarkRead(ctx context.Context, userID string, upTo int64, at time.Time) error
}

type TokenVerifier interface {
	Verify(ctx context.Context, token string) (auth.Claims, error)
}

const (
	heartbeat    = 25 * time.Second
	replayLimit  = 100
	recentLimit  = 30
	writeTimeout = 10 * time.Second
)

// EnableNotifications liga as rotas de avisos: o core-bank publica, o site escuta.
func (s *Server) EnableNotifications(n Notifications, tokens TokenVerifier) *Server {
	s.notes, s.tokens, s.hub = n, tokens, notify.NewHub()
	return s
}

func (s *Server) liveRoutes(mux *http.ServeMux) {
	if s.notes == nil {
		return
	}
	mux.HandleFunc("POST /v1/events", s.publish)
	mux.HandleFunc("GET /v1/notifications", s.recent)
	mux.HandleFunc("POST /v1/notifications/read", s.markRead)
	mux.HandleFunc("GET /v1/stream", s.stream)
}

// publish recebe um aviso do core-bank, assinado com o mesmo segredo das análises.
func (s *Server) publish(w http.ResponseWriter, r *http.Request) {
	body, ok := s.readSigned(w, r)
	if !ok {
		return
	}
	var e notify.Event
	decoder := json.NewDecoder(bytesReader(body))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&e); err != nil || !validEvent(e) {
		problem(w, http.StatusBadRequest, "event.invalid", "Aviso inválido.")
		return
	}
	n, created, err := s.notes.SaveEvent(r.Context(), e)
	if err != nil {
		s.fail(w, r.Header.Get("X-Trace-Id"), err)
		return
	}
	if created {
		s.hub.Publish(e.UserID, n)
	}
	write(w, http.StatusAccepted, map[string]int64{"seq": n.Seq})
}

func validEvent(e notify.Event) bool {
	title, body := utf8.RuneCountInString(e.Title), utf8.RuneCountInString(e.Body)
	return uuid.MatchString(e.ID) && uuid.MatchString(e.UserID) && notify.Kinds[e.Kind] &&
		title >= 1 && title <= 80 && body <= 280 && !e.CreatedAt.IsZero() &&
		(e.Link == "" || (strings.HasPrefix(e.Link, "/app/") && len(e.Link) <= 120))
}

func (s *Server) recent(w http.ResponseWriter, r *http.Request) {
	claims, ok := s.authenticate(w, r)
	if !ok {
		return
	}
	items, unread, err := s.notes.Recent(r.Context(), claims.Subject, recentLimit)
	if err != nil {
		s.fail(w, "", err)
		return
	}
	if items == nil {
		items = []notify.Notification{}
	}
	write(w, http.StatusOK, map[string]any{"items": items, "unread": unread})
}

func (s *Server) markRead(w http.ResponseWriter, r *http.Request) {
	claims, ok := s.authenticate(w, r)
	if !ok {
		return
	}
	var request struct {
		UpTo int64 `json:"upTo"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1<<10)).Decode(&request); err != nil || request.UpTo < 0 {
		problem(w, http.StatusBadRequest, "request.invalid", "Pedido inválido.")
		return
	}
	if err := s.notes.MarkRead(r.Context(), claims.Subject, request.UpTo, s.now()); err != nil {
		s.fail(w, "", err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// stream mantém a conexão aberta e manda cada aviso novo na hora (Server-Sent Events).
// O token vai no cabeçalho Authorization, nunca na URL, e a conexão fecha quando ele vence.
func (s *Server) stream(w http.ResponseWriter, r *http.Request) {
	claims, ok := s.authenticate(w, r)
	if !ok {
		return
	}
	live, cancel, err := s.hub.Subscribe(claims.Subject)
	if errors.Is(err, notify.ErrTooMany) {
		problem(w, http.StatusTooManyRequests, "stream.too_many", "Conexões demais abertas.")
		return
	}
	defer cancel()

	// Inscrito antes de ler o que falta: nada que chegue no meio do caminho se perde.
	var missed []notify.Notification
	last := int64(-1)
	if raw := r.Header.Get("Last-Event-ID"); raw != "" {
		after, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || after < 0 {
			problem(w, http.StatusBadRequest, "request.invalid", "Last-Event-ID inválido.")
			return
		}
		if missed, err = s.notes.Since(r.Context(), claims.Subject, after, replayLimit); err != nil {
			s.fail(w, "", err)
			return
		}
		last = after
	}

	rc := http.NewResponseController(w)
	// O ReadTimeout do servidor cancelaria a conexão parada; aqui ela fica aberta de propósito.
	_ = rc.SetReadDeadline(time.Time{})
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	send := func(format string, args ...any) bool {
		_ = rc.SetWriteDeadline(time.Now().Add(writeTimeout))
		if _, err := fmt.Fprintf(w, format, args...); err != nil {
			return false
		}
		return rc.Flush() == nil
	}
	deliver := func(n notify.Notification) bool {
		if n.Seq <= last {
			return true
		}
		last = n.Seq
		data, _ := json.Marshal(n)
		return send("id: %d\nevent: notification\ndata: %s\n\n", n.Seq, data)
	}

	if !send("retry: 3000\n: conectado\n\n") {
		return
	}
	for _, n := range missed {
		if !deliver(n) {
			return
		}
	}
	expired := time.NewTimer(claims.ExpiresAt.Sub(s.now()))
	defer expired.Stop()
	ping := time.NewTicker(s.heartbeat())
	defer ping.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case n := <-live:
			if !deliver(n) {
				return
			}
		case <-ping.C:
			if !send(": ping\n\n") {
				return
			}
		case <-expired.C:
			// O site renova o token e reconecta com o Last-Event-ID.
			send("event: expired\ndata: {}\n\n")
			return
		}
	}
}

func (s *Server) heartbeat() time.Duration {
	if s.ping > 0 {
		return s.ping
	}
	return heartbeat
}

func (s *Server) authenticate(w http.ResponseWriter, r *http.Request) (auth.Claims, bool) {
	token, found := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
	if !found || token == "" {
		problem(w, http.StatusUnauthorized, "auth.required", "Entre para ver seus avisos.")
		return auth.Claims{}, false
	}
	claims, err := s.tokens.Verify(r.Context(), token)
	if errors.Is(err, auth.ErrInvalid) {
		problem(w, http.StatusUnauthorized, "auth.invalid", "Sessão expirada.")
		return auth.Claims{}, false
	}
	if err != nil {
		s.log.Error("não deu para conferir o token", "error", err)
		problem(w, http.StatusServiceUnavailable, "auth.unavailable", "Não deu para conferir a sessão agora.")
		return auth.Claims{}, false
	}
	return claims, true
}
