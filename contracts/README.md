# Contrato entre o core-bank e o sentinel

O `core-bank` (Java) pede uma análise; o `sentinel` (Go) responde com nota, decisão e motivos.
Os dois lados têm testes que leem estes arquivos: se um muda o formato, o teste do outro quebra.

## `POST /v1/analyses`

Corpo: [analysis-request.json](analysis-request.json). Resposta: [analysis-response.json](analysis-response.json).

Cabeçalhos obrigatórios:

| Cabeçalho | Conteúdo |
|---|---|
| `X-Lastro-Timestamp` | Segundos desde 1970, em UTC |
| `X-Lastro-Signature` | HMAC-SHA256 em hexadecimal de `timestamp + "." + corpo`, com o segredo compartilhado |
| `X-Trace-Id` | Opcional; aparece nos logs dos dois serviços |

O sentinel recusa assinatura errada e horário com mais de 5 minutos de diferença.

A mesma `transferId` sempre devolve a mesma análise: repetir a chamada é seguro.

## Decisões

| Nota | Decisão |
|---|---|
| 0 a 39 | `APPROVED` |
| 40 a 69 | `REVIEW` |
| 70 ou mais | `DENIED` |

## `POST /v1/events`

Aviso para um cliente, como "Pix recebido". Corpo: [notification-event.json](notification-event.json),
assinado do mesmo jeito que a análise. O sentinel guarda o aviso e manda na hora para o site do
cliente, se ele estiver com o app aberto.

- `kind`: `PIX_RECEIVED`, `PIX_SENT`, `PIX_REVIEW`, `PIX_DENIED`, `PIX_CANCELLED` ou `SUPPORT`.
- `title` até 80 caracteres, `body` até 280. `link` é opcional e só pode apontar para dentro do app (`/app/...`).
- O mesmo `id` mandado duas vezes vira um aviso só: o core-bank pode tentar de novo sem medo.

## Avisos no site

O site fala com o sentinel com o token de acesso do core-bank, que o sentinel confere pela chave
pública em `/.well-known/jwks.json` (só RS256).

| Rota | O que faz |
|---|---|
| `GET /v1/notifications` | Os 30 avisos mais recentes e quantos não foram lidos |
| `POST /v1/notifications/read` | `{"upTo": seq}` marca como lido até ali |
| `GET /v1/stream` | Server-Sent Events. `Last-Event-ID` reenvia o que chegou enquanto estava desconectado; fecha com `event: expired` quando o token vence |
