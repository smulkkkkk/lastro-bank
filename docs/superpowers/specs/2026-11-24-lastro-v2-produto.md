# Lastro v2: o produto

Data: 2026-11-24. Amplia a [especificação base](2026-11-24-lastro-bank-design.md). Onde as duas
discordam em visual e ordem das etapas, vale esta. Regras de dinheiro e segurança não mudam.

## Identidade

- **Marca:** moeda com cauda, e o nome desenhado com o O final virando peixe.
- **Cena:** peixe-beta translúcido nadando na névoa, informação só nos cantos.
- **Mascote:** Lino, o baiacu. Aparece em entrada, conquistas, telas vazias e suporte, nunca no meio
  de uma decisão sobre dinheiro. Infla conforme as caixinhas crescem.
- **Moeda do app:** bolhas.
- **Cores:** névoa (claro) e abissal (escuro), com brasa como único destaque. Cartões pretos.
- **Formatos:** celular com barra inferior, tablet com trilho lateral e PC com menu completo.
  Instalável como aplicativo (PWA).

## A ideia que nenhum outro banco tem: Rastro

Tocar em uma compra mostra **de qual entrada veio aquele dinheiro**: "este tênis saiu do depósito de
terça". Como o razão guarda cada centavo com origem e destino, o banco consegue seguir o dinheiro na
ordem em que ele entrou (primeiro a entrar, primeiro a sair). É a linha de lastro aplicada à vida real.

## O que foi pedido, e como será

| Pedido | Como | Etapa |
|---|---|---|
| Logo, mascote, celular/tablet/PC | Identidade Lino, PWA | 4 ✅ |
| Trocar cartões em 3D | Carteira com arrasto e inclinação | 4 ✅ |
| Ver ou esconder o dinheiro | Botão de olho em todas as telas | 4 ✅ |
| Débito e bloqueio de cartão | Cartões na API | 4 ✅ |
| Pix | Chaves e transferências internas, sem ligação com o Pix real | 5 |
| Boleto e recarga | Contas fictícias pagas pelo razão | 5 |
| Histórico de compras | Compras simuladas no cartão, extrato por cartão | 5 |
| Rastro | Consulta sobre o razão | 5 |
| Crédito e conversão de limite em débito | Limite, fatura e conversão com taxa exibida antes | 6 |
| Caixinhas com % do CDI | CDI público do Banco Central, rendimento diário no razão | 6 |
| Empréstimo | Simulação com parcelas e custo total, dinheiro fictício | 6 |
| Investir, bitcoin e moedas | Cotações públicas, compra e venda simuladas | 7 |
| Notícias | Feed de fonte pública, sempre com link para a origem | 7 |
| Antifraude em Go | Como na especificação base | 8 |
| Chat com IA | Assistente que responde e explica, sem poder mover dinheiro | 9 |
| WhatsApp pelo n8n | Webhook que abre atendimento humano | 9 |
| Bolhas e descontos em lojas | Cashback na moeda do app e lojas parceiras fictícias | 10 |
| Conectar outros bancos | Open Finance simulado, com consentimento e revogação | 10 |
| Aproximação por celular e relógio | Simulação animada | 10 |
| Imagens para o GitHub | Capturas geradas por script | 4 ✅ |

## Limites honestos

- **Pagar por aproximação** de verdade exige contrato com bandeira e aplicativo nativo. Aqui é simulação.
- **Outros bancos** exigem autorização do Banco Central. Aqui os bancos conectados são fictícios.
- **Lojas** são inventadas: usar marca real sugeriria parceria que não existe.
- **IA e WhatsApp** dependem de chave de API e de um n8n do dono do projeto, guardados fora do
  repositório. Sem eles, o chat responde só perguntas prontas e diz isso.
- **Investimentos** usam cotação real e dinheiro fictício, sem recomendar nada a ninguém.
- O app não mostra botão para o que não funciona. O que falta aparece em uma lista, na tela Conta.
