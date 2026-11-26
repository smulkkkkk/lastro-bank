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
