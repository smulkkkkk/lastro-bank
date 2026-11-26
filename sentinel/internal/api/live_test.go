package api

import (
	"bufio"
	"context"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/smulkkkkk/lastro-bank/sentinel/internal/auth"
	"github.com/smulkkkkk/lastro-bank/sentinel/internal/notify"
)

const (
	alice = "0a000000-0000-4000-8000-00000000000a"
	bob   = "0b000000-0000-4000-8000-00000000000b"
)

// fakeTokens aceita "token-<usuário>" e "curto-<usuário>" (vence em 300 ms).
type fakeTokens struct{ now func() time.Time }

func (f fakeTokens) Verify(_ context.Context, token string) (auth.Claims, error) {
	if user, ok := strings.CutPrefix(token, "token-"); ok {
		return auth.Claims{Subject: user, ExpiresAt: f.now().Add(time.Hour)}, nil
	}
	if user, ok := strings.CutPrefix(token, "curto-"); ok {
		return auth.Claims{Subject: user, ExpiresAt: f.now().Add(300 * time.Millisecond)}, nil
	}
	return auth.Claims{}, auth.ErrInvalid
}

func liveServer(t *testing.T) *httptest.Server {
	t.Helper()
	s := NewServer(NewMemoryStore(), secret, slog.New(slog.NewTextHandler(io.Discard, nil)))
	s.EnableNotifications(notify.NewMemory(), fakeTokens{now: time.Now})
	s.ping = 50 * time.Millisecond
	srv := httptest.NewServer(s.Routes())
	t.Cleanup(srv.Close)
	return srv
}

func event(n int, user, kind string) string {
	return fmt.Sprintf(`{"id":%q,"userId":%q,"kind":%q,"title":"Pix recebido","body":"R$ 10,00 de Ana","link":"/app/extrato","createdAt":%q}`,
		id(n), user, kind, time.Now().UTC().Format(time.RFC3339))
}

func publish(t *testing.T, srv *httptest.Server, payload string, signed bool) int {
	t.Helper()
	timestamp := strconv.FormatInt(time.Now().Unix(), 10)
	request, _ := http.NewRequest(http.MethodPost, srv.URL+"/v1/events", strings.NewReader(payload))
	request.Header.Set("X-Lastro-Timestamp", timestamp)
	signature := "00"
	if signed {
		signature = Sign(secret, timestamp, []byte(payload))
	}
	request.Header.Set("X-Lastro-Signature", signature)
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		t.Fatal(err)
	}
	response.Body.Close()
	return response.StatusCode
}

type stream struct {
	lines  chan string
	status int
	cancel func()
}

func open(t *testing.T, srv *httptest.Server, token, lastID string) *stream {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	request, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/v1/stream", nil)
	request.Header.Set("Authorization", "Bearer "+token)
	if lastID != "" {
		request.Header.Set("Last-Event-ID", lastID)
	}
	response, err := http.DefaultClient.Do(request)
	if err != nil {
		cancel()
		t.Fatal(err)
	}
	s := &stream{lines: make(chan string, 64), status: response.StatusCode, cancel: cancel}
	t.Cleanup(cancel)
	go func() {
		defer close(s.lines)
		defer response.Body.Close()
		scanner := bufio.NewScanner(response.Body)
		for scanner.Scan() {
			s.lines <- scanner.Text()
		}
	}()
	return s
}

// next espera a próxima linha que começa com o prefixo, ignorando o resto.
func (s *stream) next(t *testing.T, prefix string) string {
	t.Helper()
	deadline := time.After(2 * time.Second)
	for {
		select {
		case line, ok := <-s.lines:
			if !ok {
				t.Fatalf("stream fechou esperando %q", prefix)
			}
			if strings.HasPrefix(line, prefix) {
				return line
			}
		case <-deadline:
			t.Fatalf("nada com %q", prefix)
		}
	}
}

func (s *stream) nothing(t *testing.T, prefix string, wait time.Duration) {
	t.Helper()
	deadline := time.After(wait)
	for {
		select {
		case line, ok := <-s.lines:
			if ok && strings.HasPrefix(line, prefix) {
				t.Fatalf("não esperava %q", line)
			}
			if !ok {
				return
			}
		case <-deadline:
			return
		}
	}
}

func TestEventReachesTheConnectedCustomerOnly(t *testing.T) {
	srv := liveServer(t)
	a := open(t, srv, "token-"+alice, "")
	b := open(t, srv, "token-"+bob, "")
	a.next(t, ": conectado")
	b.next(t, ": conectado")

	if code := publish(t, srv, event(1, alice, "PIX_RECEIVED"), true); code != http.StatusAccepted {
		t.Fatalf("publicar: %d", code)
	}
	if line := a.next(t, "data: "); !strings.Contains(line, `"title":"Pix recebido"`) || !strings.Contains(line, `"seq":1`) {
		t.Fatal(line)
	}
	b.nothing(t, "data: ", 200*time.Millisecond)
}

func TestUnsignedOrInvalidEventsAreRefused(t *testing.T) {
	srv := liveServer(t)
	cases := map[string]struct {
		payload string
		signed  bool
		want    int
	}{
		"sem assinatura":   {event(1, alice, "PIX_RECEIVED"), false, http.StatusUnauthorized},
		"tipo inventado":   {event(2, alice, "PROMOCAO"), true, http.StatusBadRequest},
		"link para fora":   {strings.Replace(event(3, alice, "PIX_SENT"), "/app/extrato", "https://golpe.example", 1), true, http.StatusBadRequest},
		"campo a mais":     {strings.Replace(event(4, alice, "PIX_SENT"), `"kind"`, `"extra":1,"kind"`, 1), true, http.StatusBadRequest},
		"título comprido":  {strings.Replace(event(5, alice, "PIX_SENT"), "Pix recebido", strings.Repeat("a", 81), 1), true, http.StatusBadRequest},
		"usuário estranho": {event(6, "todos", "PIX_SENT"), true, http.StatusBadRequest},
	}
	for name, c := range cases {
		if got := publish(t, srv, c.payload, c.signed); got != c.want {
			t.Errorf("%s: %d, esperava %d", name, got, c.want)
		}
	}
}

func TestRepeatedEventIsDeliveredOnce(t *testing.T) {
	srv := liveServer(t)
	a := open(t, srv, "token-"+alice, "")
	a.next(t, ": conectado")
	publish(t, srv, event(1, alice, "PIX_RECEIVED"), true)
	publish(t, srv, event(1, alice, "PIX_RECEIVED"), true)
	a.next(t, "data: ")
	a.nothing(t, "data: ", 200*time.Millisecond)
}

func TestReconnectReplaysWhatWasMissed(t *testing.T) {
	srv := liveServer(t)
	for n := 1; n <= 3; n++ {
		publish(t, srv, event(n, alice, "PIX_RECEIVED"), true)
	}
	a := open(t, srv, "token-"+alice, "1")
	if line := a.next(t, "id: "); line != "id: 2" {
		t.Fatal(line)
	}
	if line := a.next(t, "id: "); line != "id: 3" {
		t.Fatal(line)
	}
}

func TestStreamNeedsAValidToken(t *testing.T) {
	srv := liveServer(t)
	for _, token := range []string{"", "lixo"} {
		if s := open(t, srv, token, ""); s.status != http.StatusUnauthorized {
			t.Errorf("token %q: %d", token, s.status)
		}
	}
}

func TestStreamClosesWhenTheTokenExpires(t *testing.T) {
	srv := liveServer(t)
	a := open(t, srv, "curto-"+alice, "")
	a.next(t, "event: expired")
}

func TestStreamSendsHeartbeats(t *testing.T) {
	srv := liveServer(t)
	open(t, srv, "token-"+alice, "").next(t, ": ping")
}

func TestTooManyStreamsPerCustomer(t *testing.T) {
	srv := liveServer(t)
	for i := 0; i < notify.MaxPerUser; i++ {
		open(t, srv, "token-"+alice, "").next(t, ": conectado")
	}
	if s := open(t, srv, "token-"+alice, ""); s.status != http.StatusTooManyRequests {
		t.Fatalf("%d", s.status)
	}
}

func TestListAndMarkRead(t *testing.T) {
	srv := liveServer(t)
	publish(t, srv, event(1, alice, "PIX_RECEIVED"), true)
	publish(t, srv, event(2, alice, "PIX_SENT"), true)
	publish(t, srv, event(3, bob, "PIX_SENT"), true)
	get := func(token string) string {
		request, _ := http.NewRequest(http.MethodGet, srv.URL+"/v1/notifications", nil)
		request.Header.Set("Authorization", "Bearer "+token)
		response, err := http.DefaultClient.Do(request)
		if err != nil {
			t.Fatal(err)
		}
		defer response.Body.Close()
		raw, _ := io.ReadAll(response.Body)
		return string(raw)
	}
	if body := get("token-" + alice); !strings.Contains(body, `"unread":2`) || strings.Count(body, `"seq"`) != 2 {
		t.Fatal(body)
	}
	request, _ := http.NewRequest(http.MethodPost, srv.URL+"/v1/notifications/read", strings.NewReader(`{"upTo":2}`))
	request.Header.Set("Authorization", "Bearer token-"+alice)
	if response, err := http.DefaultClient.Do(request); err != nil || response.StatusCode != http.StatusNoContent {
		t.Fatal(err, response.StatusCode)
	}
	if body := get("token-" + alice); !strings.Contains(body, `"unread":0`) {
		t.Fatal(body)
	}
	// Marcar como lido não mexe nos avisos de outra pessoa.
	if body := get("token-" + bob); !strings.Contains(body, `"unread":1`) {
		t.Fatal(body)
	}
}

// O exemplo de contracts/ é o que o core-bank manda; ele tem que passar aqui.
func TestNotificationContractExample(t *testing.T) {
	raw, err := os.ReadFile("../../../contracts/notification-event.json")
	if err != nil {
		t.Fatal(err)
	}
	payload := strings.Replace(string(raw), "2026-11-25T15:00:00Z", time.Now().UTC().Format(time.RFC3339), 1)
	if code := publish(t, liveServer(t), payload, true); code != http.StatusAccepted {
		t.Fatalf("%d", code)
	}
}
