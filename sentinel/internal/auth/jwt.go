// Package auth confere os tokens de acesso emitidos pelo core-bank.
//
// O sentinel não tem segredo nenhum de usuário: busca a chave pública no JWKS do core-bank e só
// aceita RS256. Um token "alg: none" ou assinado com HMAC é recusado antes de qualquer conta.
package auth

import (
	"context"
	"crypto"
	"crypto/rsa"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math/big"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Leeway cobre relógios um pouco fora entre os dois serviços.
const Leeway = 30 * time.Second

// refetchEvery limita quantas vezes um token com kid desconhecido faz buscar o JWKS de novo.
const refetchEvery = 30 * time.Second

var ErrInvalid = errors.New("token inválido")

var uuid = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)

type Claims struct {
	Subject   string
	Role      string
	ExpiresAt time.Time
}

type Verifier struct {
	url      string
	issuer   string
	audience string
	client   *http.Client
	now      func() time.Time

	mu      sync.Mutex
	keys    map[string]*rsa.PublicKey
	fetched time.Time
}

func NewVerifier(jwksURL, issuer, audience string) *Verifier {
	return &Verifier{url: jwksURL, issuer: issuer, audience: audience,
		client: &http.Client{Timeout: 5 * time.Second}, now: time.Now}
}

type header struct {
	Alg string `json:"alg"`
	Kid string `json:"kid"`
}

type payload struct {
	Iss  string          `json:"iss"`
	Sub  string          `json:"sub"`
	Aud  json.RawMessage `json:"aud"`
	Exp  float64         `json:"exp"`
	Nbf  float64         `json:"nbf"`
	Role string          `json:"role"`
}

func (v *Verifier) Verify(ctx context.Context, token string) (Claims, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return Claims{}, ErrInvalid
	}
	var h header
	if err := decodePart(parts[0], &h); err != nil || h.Alg != "RS256" {
		return Claims{}, ErrInvalid
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil {
		return Claims{}, ErrInvalid
	}
	keys, err := v.keysFor(ctx, h.Kid)
	if err != nil {
		return Claims{}, err
	}
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	verified := false
	for _, key := range keys {
		if rsa.VerifyPKCS1v15(key, crypto.SHA256, digest[:], signature) == nil {
			verified = true
			break
		}
	}
	if !verified {
		return Claims{}, ErrInvalid
	}

	var p payload
	if err := decodePart(parts[1], &p); err != nil {
		return Claims{}, ErrInvalid
	}
	now := v.now()
	expires := time.Unix(int64(p.Exp), 0)
	switch {
	case p.Iss != v.issuer, !hasAudience(p.Aud, v.audience), !uuid.MatchString(p.Sub):
		return Claims{}, ErrInvalid
	case p.Exp == 0 || now.After(expires.Add(Leeway)):
		return Claims{}, ErrInvalid
	case p.Nbf != 0 && now.Add(Leeway).Before(time.Unix(int64(p.Nbf), 0)):
		return Claims{}, ErrInvalid
	}
	return Claims{Subject: p.Sub, Role: p.Role, ExpiresAt: expires}, nil
}

// keysFor devolve a chave do kid, ou todas quando o token não traz kid.
func (v *Verifier) keysFor(ctx context.Context, kid string) ([]*rsa.PublicKey, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	known := v.keys[kid] != nil || (kid == "" && len(v.keys) > 0)
	if !known && (v.keys == nil || v.now().Sub(v.fetched) > refetchEvery) {
		keys, err := v.fetch(ctx)
		if err != nil {
			return nil, err
		}
		v.keys, v.fetched = keys, v.now()
	}
	if kid != "" {
		if key := v.keys[kid]; key != nil {
			return []*rsa.PublicKey{key}, nil
		}
		return nil, ErrInvalid
	}
	all := make([]*rsa.PublicKey, 0, len(v.keys))
	for _, key := range v.keys {
		all = append(all, key)
	}
	return all, nil
}

func (v *Verifier) fetch(ctx context.Context) (map[string]*rsa.PublicKey, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, v.url, nil)
	if err != nil {
		return nil, err
	}
	response, err := v.client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("buscar JWKS: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("buscar JWKS: status %d", response.StatusCode)
	}
	var set struct {
		Keys []struct {
			Kty string `json:"kty"`
			Kid string `json:"kid"`
			N   string `json:"n"`
			E   string `json:"e"`
		} `json:"keys"`
	}
	if err := json.NewDecoder(io.LimitReader(response.Body, 64<<10)).Decode(&set); err != nil {
		return nil, fmt.Errorf("ler JWKS: %w", err)
	}
	keys := map[string]*rsa.PublicKey{}
	for _, k := range set.Keys {
		if k.Kty != "RSA" {
			continue
		}
		n, errN := base64.RawURLEncoding.DecodeString(k.N)
		e, errE := base64.RawURLEncoding.DecodeString(k.E)
		if errN != nil || errE != nil || len(e) > 4 {
			continue
		}
		exponent := int(new(big.Int).SetBytes(e).Int64())
		key := &rsa.PublicKey{N: new(big.Int).SetBytes(n), E: exponent}
		if key.N.BitLen() < 2048 || exponent < 3 {
			continue
		}
		keys[k.Kid] = key
	}
	if len(keys) == 0 {
		return nil, errors.New("JWKS sem chave RSA utilizável")
	}
	return keys, nil
}

func decodePart(part string, into any) error {
	raw, err := base64.RawURLEncoding.DecodeString(part)
	if err != nil {
		return err
	}
	return json.Unmarshal(raw, into)
}

// O padrão aceita aud como texto ou lista.
func hasAudience(raw json.RawMessage, want string) bool {
	var one string
	if json.Unmarshal(raw, &one) == nil {
		return one == want
	}
	var many []string
	if json.Unmarshal(raw, &many) != nil {
		return false
	}
	for _, aud := range many {
		if aud == want {
			return true
		}
	}
	return false
}
