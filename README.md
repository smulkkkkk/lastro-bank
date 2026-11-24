# Lastro Bank

Banco digital de demonstração em **Java**, **Go**, **PostgreSQL** e **React**. Todo o dinheiro é
fictício.

> Em construção: a base do `core-bank` e o site já funcionam. O andamento está no fim da página.

## O que já funciona

- **Razão contábil em partidas dobradas.** O próprio PostgreSQL recusa lançamento desbalanceado,
  alterado ou apagado, saldo negativo e saldo mudado por fora.
- **Concorrência segura.** Contas travadas em ordem fixa: 200 movimentos cruzados em paralelo
  mantêm a soma e não entram em deadlock.
- **Idempotência.** Depósitos iguais enviados ao mesmo tempo viram um só.
- **Login.** Senha com Argon2id, JWT RS256 com JWKS, renovação rotativa que derruba a sessão se um
  token for reutilizado, e bloqueio após 5 erros.
- **Extrato** com cursor e saldo acumulado por window function.
- **Erros** no padrão RFC 9457, com código estável e `traceId`.

## Rodar

Precisa do JDK 25 e do Node 22.12 ou mais novo. O PostgreSQL vem embutido, sem Docker.

No Windows, dê dois cliques em `iniciar.cmd`: ele sobe a API e o site e abre
`http://localhost:5173`.

Para rodar só a API e os testes dela:

```powershell
cd core-bank
.\mvnw.cmd test
.\mvnw.cmd spring-boot:test-run
```

A API sobe em `http://localhost:8080`.

| Método | Rota | Função |
|---|---|---|
| POST | `/api/v1/auth/register` | Cadastro; abre conta com R$ 1.000,00 fictícios |
| POST | `/api/v1/auth/login` | Token de acesso e cookie de renovação |
| POST | `/api/v1/auth/refresh` | Renova a sessão |
| POST | `/api/v1/auth/logout` | Encerra a sessão |
| GET | `/api/v1/accounts` | Contas do cliente |
| GET | `/api/v1/accounts/{id}` | Uma conta |
| POST | `/api/v1/accounts/{id}/deposits` | Depósito simulado (exige `Idempotency-Key`) |
| GET | `/api/v1/accounts/{id}/statement` | Extrato paginado |
| GET | `/.well-known/jwks.json` | Chave pública dos tokens |

## Andamento

- [x] 1. Base: banco, login, contas, depósito e extrato
- [ ] 2. Transferências
- [ ] 3. Sentinel: antifraude e notificações em Go
- [ ] 4. Site em React (pronto: página inicial, cadastro, login, painel, depósito e extrato; falta transferir)
- [ ] 5. Cofrinhos, cartões e agendamentos
- [ ] 6. Backoffice
- [ ] 7. Docker, CI, métricas e publicação

O projeto completo está na
[especificação](docs/superpowers/specs/2026-11-24-lastro-bank-design.md).
