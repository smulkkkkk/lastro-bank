package auth

import (
	"context"
	"crypto"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

const user = "0b8c3e1a-7d42-4c1f-8e6a-5f9d2b4c7e31"

var now = time.Date(2026, 11, 25, 15, 0, 0, 0, time.UTC)

type keyServer struct {
	key     *rsa.PrivateKey
	kid     string
	fetches atomic.Int32
	url     string
}

func newKeyServer(t *testing.T, kid string) *keyServer {
	t.Helper()
	key, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		t.Fatal(err)
	}
	ks := &keyServer{key: key, kid: kid}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		ks.fetches.Add(1)
		_ = json.NewEncoder(w).Encode(map[string]any{"keys": []map[string]string{{
			"kty": "RSA", "kid": ks.kid,
			"n": base64.RawURLEncoding.EncodeToString(ks.key.N.Bytes()),
			"e": base64.RawURLEncoding.EncodeToString(big.NewInt(int64(ks.key.E)).Bytes()),
		}}})
	}))
	t.Cleanup(srv.Close)
	ks.url = srv.URL
	return ks
}

func (ks *keyServer) verifier() *Verifier {
	v := NewVerifier(ks.url, "lastro-core", "lastro")
	v.now = func() time.Time { return now }
	return v
}

func encode(v any) string {
	raw, _ := json.Marshal(v)
	return base64.RawURLEncoding.EncodeToString(raw)
}

func claims(change func(map[string]any)) map[string]any {
	c := map[string]any{"iss": "lastro-core", "aud": []string{"lastro"}, "sub": user, "role": "CUSTOMER",
		"exp": now.Add(10 * time.Minute).Unix(), "iat": now.Unix()}
	if change != nil {
		change(c)
	}
	return c
}

func (ks *keyServer) sign(t *testing.T, head map[string]any, body map[string]any) string {
	t.Helper()
	unsigned := encode(head) + "." + encode(body)
	digest := sha256.Sum256([]byte(unsigned))
	signature, err := rsa.SignPKCS1v15(rand.Reader, ks.key, crypto.SHA256, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	return unsigned + "." + base64.RawURLEncoding.EncodeToString(signature)
}

func TestValidTokenIsAccepted(t *testing.T) {
	ks := newKeyServer(t, "k1")
	c, err := ks.verifier().Verify(context.Background(), ks.sign(t, map[string]any{"alg": "RS256", "kid": "k1"}, claims(nil)))
	if err != nil || c.Subject != user || c.Role != "CUSTOMER" {
		t.Fatalf("%v %+v", err, c)
	}
}

func TestTokenWithoutKidTriesTheKnownKeys(t *testing.T) {
	ks := newKeyServer(t, "")
	if _, err := ks.verifier().Verify(context.Background(), ks.sign(t, map[string]any{"alg": "RS256"}, claims(nil))); err != nil {
		t.Fatal(err)
	}
}

func TestBadTokensAreRefused(t *testing.T) {
	ks := newKeyServer(t, "k1")
	head := map[string]any{"alg": "RS256", "kid": "k1"}
	good := ks.sign(t, head, claims(nil))
	other := newKeyServer(t, "k1")

	// "alg: HS256" assinado com a chave pública como segredo: o golpe clássico de confusão de algoritmo.
	hsUnsigned := encode(map[string]any{"alg": "HS256", "kid": "k1"}) + "." + encode(claims(nil))
	mac := hmac.New(sha256.New, ks.key.N.Bytes())
	mac.Write([]byte(hsUnsigned))

	cases := map[string]string{
		"vencido":           ks.sign(t, head, claims(func(c map[string]any) { c["exp"] = now.Add(-time.Minute).Unix() })),
		"sem validade":      ks.sign(t, head, claims(func(c map[string]any) { delete(c, "exp") })),
		"ainda não vale":    ks.sign(t, head, claims(func(c map[string]any) { c["nbf"] = now.Add(time.Hour).Unix() })),
		"outro emissor":     ks.sign(t, head, claims(func(c map[string]any) { c["iss"] = "outro" })),
		"outro público":     ks.sign(t, head, claims(func(c map[string]any) { c["aud"] = []string{"outro"} })),
		"sujeito estranho":  ks.sign(t, head, claims(func(c map[string]any) { c["sub"] = "admin" })),
		"alg none":          encode(map[string]any{"alg": "none"}) + "." + encode(claims(nil)) + ".",
		"alg HS256":         hsUnsigned + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil)),
		"outra chave":       other.sign(t, head, claims(nil)),
		"conteúdo trocado":  swapPayload(good, claims(func(c map[string]any) { c["sub"] = "11111111-1111-4111-8111-111111111111" })),
		"pedaços demais":    good + ".x",
		"assinatura quebra": good[:len(good)-4] + "!!!!",
	}
	for name, token := range cases {
		if _, err := ks.verifier().Verify(context.Background(), token); err == nil {
			t.Errorf("%s: aceitou", name)
		}
	}
}

func TestUnknownKidRefetchesAtMostEvery30Seconds(t *testing.T) {
	ks := newKeyServer(t, "k1")
	v := ks.verifier()
	ctx := context.Background()
	if _, err := v.Verify(ctx, ks.sign(t, map[string]any{"alg": "RS256", "kid": "k1"}, claims(nil))); err != nil {
		t.Fatal(err)
	}
	// O core-bank trocou de chave.
	ks.key, _ = rsa.GenerateKey(rand.Reader, 2048)
	ks.kid = "k2"
	rotated := ks.sign(t, map[string]any{"alg": "RS256", "kid": "k2"}, claims(nil))
	if _, err := v.Verify(ctx, rotated); err == nil {
		t.Fatal("buscou de novo antes de 30 segundos")
	}
	now = now.Add(31 * time.Second)
	defer func() { now = now.Add(-31 * time.Second) }()
	if _, err := v.Verify(ctx, rotated); err != nil {
		t.Fatal(err)
	}
	if got := ks.fetches.Load(); got != 2 {
		t.Fatalf("buscou %d vezes", got)
	}
}

func swapPayload(token string, body map[string]any) string {
	parts := strings.Split(token, ".")
	return parts[0] + "." + encode(body) + "." + parts[2]
}
