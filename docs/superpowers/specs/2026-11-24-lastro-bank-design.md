# Lastro Bank: especificação

Data: 2026-11-24. Banco digital de demonstração, para portfólio. Todo o dinheiro é fictício.

## 1. Objetivo

Mostrar, em um repositório que qualquer pessoa consegue rodar, domínio de Java, Go e SQL em um
problema onde errar custa caro: movimentar dinheiro sem perder, duplicar ou criar centavos.

O projeto está pronto quando:

- um visitante cria conta, recebe saldo fictício, transfere e vê o resultado ao vivo no site;
- um teste com transferências simultâneas prova que a soma dos saldos não muda;
- derrubar o serviço Go não perde nem aprova nenhuma transferência;
- `docker compose up` sobe tudo, e a CI do GitHub fica verde.

Fora do escopo: dinheiro real, integração com Pix, boletos ou bandeiras de cartão, aplicativo
mobile nativo, investimentos e empréstimos.

## 2. Arquitetura

| Parte | Tecnologia | Responsabilidade |
|---|---|---|
| `core-bank/` | Java 25, Spring Boot, JDBC, Flyway | Identidade, contas, razão contábil, transferências, cofrinhos, cartões, agenda, auditoria |
| `sentinel/` | Go, biblioteca padrão, pgx | Antifraude e notificações em tempo real |
| `web/` | React, TypeScript, Vite | Página inicial, área do cliente, backoffice |
| Banco | PostgreSQL 18 | Schemas `core` e `sentinel`, cada um com usuário próprio |

Regras de fronteira:

- Um serviço nunca lê nem escreve no schema do outro. O usuário do banco de cada um não tem
  permissão para isso.
- Só o `core-bank` movimenta dinheiro. O `sentinel` apenas opina.
- O Java chama o Go por HTTP. Cada chamada leva horário e assinatura HMAC-SHA256; o Go recusa
  assinatura inválida ou com mais de 5 minutos.

O `core-bank` usa SQL explícito (`JdbcClient`) em vez de JPA: travas, constraints e consultas são
parte do que o projeto demonstra.

Cada funcionalidade do `core-bank` fica em um pacote (`identity`, `account`, `ledger`, `transfer`,
`vault`, `card`, `schedule`, `audit`). Os pacotes só movimentam dinheiro por meio do `ledger`.

## 3. Dinheiro e razão contábil

- Valores são `BIGINT` em centavos. Não existe ponto flutuante.
- Saldo = créditos − débitos.
- `ledger_transaction` agrupa lançamentos; `ledger_entry` guarda conta, sentido e valor positivo.
- Um trigger de constraint adiado exige, no commit, soma de débitos igual à de créditos.
- Triggers bloqueiam `UPDATE`, `DELETE` e `TRUNCATE` nas duas tabelas. Correção é estorno.
- `account` guarda `balance_cents` e `held_cents`. Em contas de cliente, uma constraint garante
  `0 <= held_cents <= balance_cents`. Contas de sistema podem ficar negativas.
- Toda operação trava as contas com `SELECT ... FOR UPDATE` em ordem crescente de id, o que evita
  deadlock.
- A view `v_reconciliation` compara saldo guardado e soma dos lançamentos. Um job diário alerta
  diferença; os testes exigem zero.

Tipos de conta: `CHECKING` (cliente), `VAULT` (cofrinho), `SYSTEM` (origem dos depósitos e
destino das compras simuladas).

Cada conta nova recebe um depósito de R$ 1.000,00. O cliente pode simular depósitos de até
R$ 5.000,00 por dia.

## 4. Funcionalidades

**Identidade**
- Cadastro com nome, e-mail, CPF fictício com dígitos válidos e senha (Argon2id).
- Token de acesso JWT RS256 de 15 minutos. Chave pública em `/.well-known/jwks.json`.
- Token de renovação rotativo em cookie `HttpOnly`, `Secure`, `SameSite=Strict`. Reuso de token
  antigo derruba a família inteira de sessões.
- Segundo fator TOTP opcional. Bloqueio de 15 minutos após 5 senhas erradas.
- Papéis `CUSTOMER` e `ANALYST`.

**Chaves de transferência**
- Tipos e-mail, CPF e aleatória. Chave é única e pertence a uma conta. O formato imita o Pix, mas
  não há relação com o sistema real.

**Transferências**
- Por chave ou por agência e conta.
- O cabeçalho `Idempotency-Key` é obrigatório. Mesma chave com mesmo corpo devolve a resposta
  original; com corpo diferente, 409.
- Limite diário e limite noturno (20h às 6h, horário de São Paulo).

**Extrato**
- Paginação por cursor, filtros por período e tipo, saldo acumulado por linha (window function em
  uma function SQL), exportação CSV e PDF.

**Cofrinhos**
- Nome, meta e saldo próprios. Guardar e resgatar são lançamentos imediatos.

**Cartões virtuais**
- Cartão de débito fictício, com marca própria e só os 4 últimos dígitos armazenados.
- Bloquear, desbloquear, definir limite por compra e simular compra.

**Agendamentos**
- Transferência futura, única ou mensal. Sem saldo no dia, a execução é registrada como falha e o
  cliente é avisado.

**Backoffice (`ANALYST`)**
- Fila de transferências em revisão, com os motivos da nota. Aprovar ou negar exige justificativa.
- Analista não decide transferência em que é parte.

**Auditoria**
- Login, mudança de segurança, cartão e decisão de analista geram registro que só aceita inserção.

## 5. Transferência passo a passo

Estados: `PENDING`, `UNDER_REVIEW`, `SETTLED`, `DENIED`, `CANCELLED`. Os três últimos são finais.

1. O cliente envia a transferência. Saldo ou limite insuficiente devolve 422 e nada é criado.
2. Em uma única transação, o Java reserva o valor (`held_cents`), grava a transferência como
   `PENDING`, a resposta idempotente e um evento na tabela `outbox`.
3. Um worker Java pega eventos com `FOR UPDATE SKIP LOCKED` e chama `POST /v1/analyses` no Go.
   Nenhuma trava fica aberta durante a chamada: o worker marca o evento com um prazo de posse.
4. O Go grava a análise usando o id da transferência como chave. Repetir a chamada devolve a mesma
   decisão.
5. O Java trava a transferência, confere que ela ainda aceita a mudança e aplica:
   - `APPROVED`: lançamentos no razão, reserva liberada, estado `SETTLED`;
   - `DENIED`: reserva liberada, estado `DENIED`;
   - `REVIEW`: estado `UNDER_REVIEW`, reserva mantida.
6. Cada mudança grava outro evento na `outbox`, que vira notificação.

Falhas:

| Situação | Resultado |
|---|---|
| Go fora do ar | Tentativas com espera exponencial; `CANCELLED` depois de 10 minutos |
| Resposta do Go chega depois do cancelamento | Ignorada, porque o estado já é final |
| Java cai no meio | Prazo de posse vence e outro ciclo retoma o evento |
| Revisão sem decisão por 24 horas | `CANCELLED`, reserva devolvida |
| Evento de notificação repetido | Descartado pelo id do evento |

## 6. Antifraude e notificações (Go)

Cada regra é uma função que devolve pontos e motivo. A nota é a soma, limitada a 100.

| Regra | Pontos |
|---|---|
| Valor acima de 5 vezes a média dos últimos 30 dias | 35 |
| Primeira transferência para o destinatário, acima de R$ 500,00 | 25 |
| Mais de 5 transferências em 10 minutos | 40 |
| Entre 23h e 5h, acima de R$ 1.000,00 | 20 |
| Conta com menos de 24 horas, acima de R$ 800,00 | 25 |

Nota até 39 aprova, de 40 a 69 vai para revisão, 70 ou mais nega. O `sentinel` mantém o próprio
histórico a partir das análises que recebe. Sem histórico, a regra da média não pontua.

Notificações: `POST /v1/events` grava e distribui; `GET /v1/stream` entrega por SSE. O navegador
lê o stream com `fetch`, enviando o JWT no cabeçalho, nunca na URL. `Last-Event-ID` recupera o que
foi perdido.

## 7. API e erros

- Prefixo `/api/v1`, OpenAPI publicada pelo `core-bank`.
- Erros em `application/problem+json` (RFC 9457), com `code` estável e `traceId`.
- Conta de outro cliente devolve 404, para não revelar que existe.
- Limite de requisições em login e transferência.
- Logs em JSON com `traceId` propagado do Java ao Go. CPF, e-mail e tokens nunca vão para o log.
- Métricas Prometheus e verificações de saúde nos dois serviços.

## 8. Interface

Tema escuro por padrão, com tema claro. As referências enviadas servem de direção; marca, textos,
ilustrações e composição são próprios.

- **Das referências:** fundo grafite, verde esmeralda como cor de ação, cartões em degradê,
  blocos com borda fina, gráfico de linha no painel e dourado como detalhe de destaque.
- **Próprio do Lastro:** a "linha de lastro", um traço contínuo que liga saldo, gráfico e extrato
  e mostra de onde veio e para onde foi cada valor.

| Token | Valor |
|---|---|
| Fundo | `#12161A` |
| Superfície | `#1B2127` |
| Borda | `#2A333B` |
| Ação | `#35D0A0` |
| Destaque | `#D9B45B` |
| Texto | `#F2F5F4` |

Páginas: inicial, cadastro, login, painel, transferir, extrato, cofrinhos, cartões, agendamentos,
segurança e backoffice. Funciona de 360 px para cima, por teclado e com contraste AA. Números usam
algarismos tabulares. Cada tela trata carregamento, vazio e erro.

## 9. Testes

| Nível | O que prova |
|---|---|
| Unidade (JUnit, Go) | Regras de dinheiro, CPF, limites e cada regra de fraude |
| Banco | Razão desbalanceado não grava; lançamento não muda; saldo não fica negativo |
| Concorrência | 200 transferências cruzadas em paralelo mantêm a soma e não travam |
| Idempotência | 20 envios iguais simultâneos criam uma transferência |
| Falha | Go fora do ar, resposta atrasada e queda no meio do processamento |
| Contrato | Java e Go validam o mesmo JSON de exemplo em `contracts/` |
| Ponta a ponta (Playwright) | Cadastro, transferência, notificação e revisão |

Os testes de banco usam PostgreSQL embutido, então rodam sem Docker.

## 10. Execução

- **Local, sem Docker:** scripts PowerShell sobem PostgreSQL embutido, serviços e site. JVM com
  `-Xmx256m` e PostgreSQL com `shared_buffers=64MB`, porque a máquina de desenvolvimento tem 4 GB.
- **Com Docker:** `docker compose up`.
- **CI (GitHub Actions):** compila, testa e verifica cada parte, e constrói as imagens.
- Segredos só em variáveis de ambiente. O repositório traz `.env.example`, nunca `.env`.

## 11. Etapas

Cada etapa tem plano próprio e termina testada.

1. **Base:** estrutura, migrações, identidade (cadastro, login, renovação), contas, razão, depósito
   e extrato.
2. **Transferências:** chaves, reserva, limites, idempotência, outbox e concorrência.
3. **Sentinel:** antifraude, notificações e contrato entre os serviços.
4. **Site:** página inicial, cadastro, login, painel, transferir e extrato.
5. **Extras:** cofrinhos, cartões, agendamentos, TOTP e exportações.
6. **Backoffice:** revisão, auditoria e tela do analista.
7. **Acabamento:** Docker, CI, métricas, ponta a ponta, README com diagramas e publicação.
