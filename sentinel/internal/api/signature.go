package api

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"strconv"
	"time"
)

// MaxClockSkew é quanto o horário do pedido pode diferir do nosso. Mais que isso, pode ser um pedido
// antigo capturado e repetido.
const MaxClockSkew = 5 * time.Minute

// Sign produz a assinatura que o core-bank manda: HMAC-SHA256 de "timestamp.corpo".
func Sign(secret []byte, timestamp string, body []byte) string {
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(timestamp))
	mac.Write([]byte("."))
	mac.Write(body)
	return hex.EncodeToString(mac.Sum(nil))
}

// Verify compara em tempo constante, para o tempo de resposta não revelar a assinatura certa.
func Verify(secret []byte, timestamp, signature string, body []byte, now time.Time) bool {
	seconds, err := strconv.ParseInt(timestamp, 10, 64)
	if err != nil {
		return false
	}
	skew := now.Sub(time.Unix(seconds, 0))
	if skew > MaxClockSkew || skew < -MaxClockSkew {
		return false
	}
	expected, err := hex.DecodeString(Sign(secret, timestamp, body))
	if err != nil {
		return false
	}
	given, err := hex.DecodeString(signature)
	if err != nil {
		return false
	}
	return hmac.Equal(expected, given)
}
