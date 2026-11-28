![Lastro: banco digital de demonstração](docs/images/capa.png)

# Lastro Bank

Banco digital de demonstração em **Java**, **Go**, **PostgreSQL** e **React**. Todo o dinheiro é
fictício.

> Em construção: a base do `core-bank` e o site já funcionam. O andamento está no fim da página.

## Telas

![Página inicial no tema noite](docs/images/inicial-noite.png)

| Celular | Tablet | PC |
|---|---|---|
| ![Carteira no celular](docs/images/celular-cartoes.png) | ![Painel no tablet](docs/images/tablet-painel.png) | ![Painel no PC](docs/images/pc-painel.png) |

As imagens são geradas do app de verdade com `npm run capturas`, que também confere se alguma tela
vaza em 360 px, celular, tablet e PC.

## Como um Pix passa pelos dois serviços

```text
Site ──► core-bank (Java)                       sentinel (Go)
          │ reserva o valor e grava o pedido
          │ na outbox, na mesma transação
          │
          └─ worker ── POST assinado (HMAC) ──► regras de risco
                     ◄── nota, decisão, motivos ┘
          aprova: lança no razão
          recusa: devolve a reserva
          revisão: espera um analista (até 24 h)
```

- O Go nunca mexe em dinheiro e não tem permissão no schema do Java; o teste prova isso no banco.
- Sentinel fora do ar: o Pix espera, o worker tenta de novo com espera crescente e cancela em 10 minutos.
- Resposta atrasada não ressuscita Pix já cancelado. Analista não decide Pix em que é parte.
- Os dois lados testam os mesmos arquivos de exemplo em [contracts/](contracts/README.md), inclusive a assinatura.

## Rastro

Toque em um lançamento e veja **de onde veio aquele dinheiro**, centavo por centavo. Como o razão
guarda cada entrada e saída, uma função SQL segue o dinheiro na ordem em que ele entrou.

![Rastro de um Pix: três origens somam o valor enviado](docs/images/pc-rastro.png)

## Caixinhas

Cada caixinha é um aquário que enche até a meta, e o Lino infla conforme você guarda.

![Caixinhas rendendo com o CDI do Banco Central](docs/images/pc-caixinhas.png)

## Investimentos

![Gráfico do bitcoin com cotação real](docs/images/pc-ativo.png)

## Crédito

![Limite, faturas e compras parceladas](docs/images/pc-credito.png)

## Boleto e recarga

![Boleto de teste conferido antes de pagar](docs/images/pc-boleto.png)

- A linha digitável é conferida como num banco: dígito de cada campo (módulo 10), dígito geral (módulo 11)
  e vencimento pelo fator, que recomeçou em 1000 em fevereiro de 2025.
- Só paga boleto registrado, como na consulta que os bancos fazem antes de pagar. O botão
  "Gerar boleto de teste" cria um boleto registrado para experimentar.
- Cada boleto é pago uma vez só, mesmo com duas pessoas pagando no mesmo instante; o teste prova isso.
- Recarga em operadoras fictícias, só nos valores que elas aceitam. O extrato mostra o celular mascarado.
- Todo pagamento tem comprovante com autenticação, que dá para compartilhar ou salvar em PDF.

![Comprovante do boleto](docs/images/pc-comprovante-boleto.png)

## Bolhas e lojas parceiras

![Bolhas e cupons das lojas](docs/images/pc-bolhas.png)

- Bolhas são a moeda do app: cada uma vale R$ 0,01 e vem de cashback nas compras no cartão, com
  percentual maior nas lojas parceiras (fictícias).
- O cupom de uma loja entra sozinho na próxima compra lá, com teto e valor mínimo; o cashback é
  calculado sobre o que foi cobrado de fato.
- Bolhas não são dinheiro até a troca: só então viram um lançamento no razão. Duas trocas ao mesmo
  tempo não gastam as mesmas bolhas, e o extrato de bolhas não aceita alteração nem no banco.

## Avisos ao vivo e atendimento

![Pix recebido aparecendo na hora](docs/images/pc-aviso.png)

- Cada decisão de Pix grava o aviso na outbox, na mesma transação. Pix desfeito nunca avisa "recebido".
- O Java entrega ao sentinel (Go), que guarda e manda ao site por Server-Sent Events. O token vai no
  cabeçalho, conferido pela chave pública do core-bank (só RS256); caiu a conexão, o site reconecta e
  recebe o que perdeu pelo `Last-Event-ID`.
- O Lino responde no chat pela API do Claude, com os dados da conta só para leitura e sem poder mexer
  em dinheiro. Sem chave, ou com a API fora do ar, responde com perguntas prontas.
- "Falar com atendente" gera protocolo e manda o histórico ao webhook do n8n, que abre a conversa no WhatsApp.

![Chat com o Lino](docs/images/pc-ajuda.png)

## O que já funciona

- **Pix interno** com chaves, limite diário e noturno, e comprovante. Enviar duas vezes o mesmo pedido
  move o dinheiro uma vez só.
- **Peixe-beta em WebGL puro.** Corpo e nadadeiras calculados na placa de vídeo, sem biblioteca 3D.
- **Caixinhas com CDI real.** A taxa vem do Banco Central e o rendimento entra no razão a cada dia
  útil, sem perder fração de centavo e sem pagar o mesmo dia duas vezes.
- **Crédito de verdade no razão.** O que o cliente deve é o saldo negativo de uma conta própria, e o
  PostgreSQL recusa passar do limite. Fatura fecha dia 25 e vence dia 5, com compras parceladas.
- **Converter limite em saldo**, com a taxa mostrada antes e cobrada só se for a mesma.
- **Empréstimo pela tabela Price.** Quitar antes tira os juros dos meses que não chegaram.
- **Investimentos com cotação real** de bitcoin, ethereum, solana, dólar e euro, com gráfico, custo
  médio e notícias. Ordem recusada se o preço andar mais de 1% entre a tela e a execução.
- **Carteira 3D.** Três cartões que giram, flutuam e viram, com bloqueio e troca do principal.
- **Um app em três formatos**, instalável, com tema claro e botão para esconder valores.
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

Precisa do JDK 25, do Go 1.27 e do Node 22.12 ou mais novo. O PostgreSQL vem embutido, sem Docker.

No Windows, dê dois cliques em `iniciar.cmd`: ele sobe a API e o site e abre
`http://localhost:5173`.

Para ver a fila do analista, crie uma conta com o e-mail `analista@lastro.dev`.

Opcional, antes de rodar o `iniciar.cmd` (as chaves ficam só na sua máquina, nunca no repositório):

| Variável | Para quê |
|---|---|
| `LASTRO_ANTHROPIC_API_KEY` | O Lino responde com IA (Claude Opus 5.5). Sem ela, perguntas prontas |
| `LASTRO_N8N_WEBHOOK_URL` | Webhook do n8n que abre o atendimento no WhatsApp (https, ou http em localhost) |
| `LASTRO_N8N_WEBHOOK_SECRET` | Se definido, o pedido ao n8n vai assinado com HMAC, como entre os serviços |

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
| GET | `/api/v1/cards` | Cartões do cliente |
| PATCH | `/api/v1/cards/{id}` | Bloquear, aproximação ou tornar principal |
| GET | `/api/v1/accounts/{id}/entries/{id}/trace` | Rastro de um lançamento |
| GET, POST | `/api/v1/pix/keys` | Chaves do cliente |
| DELETE | `/api/v1/pix/keys/{id}` | Excluir chave |
| POST | `/api/v1/pix/lookup` | Conferir quem vai receber |
| GET | `/api/v1/pix/limits` | Quanto ainda pode sair agora |
| POST | `/api/v1/pix/transfers` | Enviar Pix (exige `Idempotency-Key`) |
| GET, POST | `/api/v1/vaults` | Caixinhas e CDI atual |
| POST | `/api/v1/vaults/{id}/deposits` | Guardar (exige `Idempotency-Key`) |
| POST | `/api/v1/vaults/{id}/withdrawals` | Resgatar (exige `Idempotency-Key`) |
| DELETE | `/api/v1/vaults/{id}` | Excluir caixinha vazia |
| POST | `/api/v1/cards/{id}/purchases` | Compra de teste no débito ou no crédito |
| GET | `/api/v1/credit` | Limite e faturas |
| POST | `/api/v1/credit/invoices/{mês}/payments` | Pagar fatura |
| GET, POST | `/api/v1/credit/conversion(s)` | Taxa e conversão de limite em saldo |
| GET, POST | `/api/v1/loans` | Oferta, contratação e parcelas |
| POST | `/api/v1/loans/{id}/payments` | Pagar parcela ou quitar |
| GET | `/api/v1/market`, `/market/{ativo}/history`, `/market/news` | Cotações, histórico e notícias |
| GET, POST | `/api/v1/invest`, `/invest/buy`, `/invest/sell` | Carteira e ordens |
| GET | `/.well-known/jwks.json` | Chave pública dos tokens |

## Andamento

- [x] Base: razão contábil, login, contas, depósito e extrato
- [x] Site e app: identidade, carteira 3D, celular, tablet e PC
- [x] Pix interno e Rastro do dinheiro
- [x] Boleto, recarga e compras no cartão
- [x] Caixinhas com CDI
- [x] Crédito, fatura, conversão de limite e empréstimo
- [x] Investimentos e notícias
- [x] Antifraude em Go, com revisão por analista
- [x] Avisos ao vivo, chat com IA e atendimento no WhatsApp
- [x] Bolhas e descontos nas lojas parceiras
- [ ] Outros bancos e aproximação simulada
- [ ] Docker, CI e publicação

O plano completo está em [produto](docs/superpowers/specs/2026-11-24-lastro-v2-produto.md) e na
[especificação base](docs/superpowers/specs/2026-11-24-lastro-bank-design.md).
