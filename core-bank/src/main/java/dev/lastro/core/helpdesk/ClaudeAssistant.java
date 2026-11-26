package dev.lastro.core.helpdesk;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.MessageCreateParams;
import dev.lastro.core.notify.Notifier;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * O Lino com IA. Responde pela API do Claude; se a API recusar, cair ou demorar, o cliente recebe a
 * resposta pronta do FAQ, nunca um erro.
 */
class ClaudeAssistant implements Assistant {

    static final String SYSTEM = """
            Você é o Lino, o baiacu mascote do Lastro, um banco digital brasileiro de demonstração.
            Atenda em português do Brasil, com simpatia e frases curtas: no máximo um parágrafo curto ou
            uma lista de até quatro itens. Trate o cliente pelo primeiro nome.

            O que o app tem: Pix (chaves CPF, e-mail e aleatória; limite de R$ 5.000,00 por dia e de
            R$ 2.000,00 entre 20h e 6h; todo Pix passa por antifraude e pode ficar em revisão por até
            24 horas com o valor reservado), caixinhas (livre a 100% do CDI, travada a 110% do CDI até
            a data escolhida), cartões físico e virtual com bloqueio na hora, crédito (fatura fecha dia
            25 e vence dia 5, parcelas em até 12 vezes), empréstimo pela tabela Price a 2,49% ao mês e
            investimentos em Bitcoin, Ethereum, Solana, dólar e euro com cotação ao vivo.

            Regras:
            - Você só conversa. Não faz Pix, pagamento, investimento nem muda nada na conta. Quando o
              cliente pedir uma operação, explique onde fazer no app.
            - Nunca peça senha, código, número completo de cartão ou documento. Se o cliente mandar,
              avise que não precisa e que ele não deve compartilhar.
            - Não dê recomendação de investimento personalizada; explique como cada produto funciona.
            - Use os dados da conta abaixo só para responder o próprio cliente. Não invente valores.
            - Se não souber, se o cliente estiver insatisfeito ou pedir uma pessoa, diga para tocar em
              "Falar com atendente", que abre um protocolo no WhatsApp.
            """;

    static final String MODEL = "claude-opus-5-5";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.of("America/Sao_Paulo"));
    private static final Logger log = LoggerFactory.getLogger(ClaudeAssistant.class);

    private final AnthropicClient client;
    private final String model;
    private final FaqAssistant faq;

    ClaudeAssistant(AnthropicClient client, String model, FaqAssistant faq) {
        this.client = client;
        this.model = model;
        this.faq = faq;
    }

    @Override
    public Reply reply(Snapshot snapshot, List<Turn> history) {
        try {
            BetaMessage message = client.beta().messages().create(params(snapshot, history));
            if (message.stopReason().filter(BetaStopReason.REFUSAL::equals).isPresent()) {
                log.info("Claude recusou a mensagem; respondendo pelo FAQ");
                return faq.reply(snapshot, history);
            }
            String text = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(block -> block.text())
                    .collect(Collectors.joining("\n"))
                    .strip();
            return text.isEmpty() ? faq.reply(snapshot, history) : new Reply(text, "CLAUDE");
        } catch (UnauthorizedException e) {
            log.error("Chave da API do Claude recusada; confira LASTRO_ANTHROPIC_API_KEY");
        } catch (RateLimitException e) {
            log.warn("Limite da API do Claude atingido");
        } catch (AnthropicServiceException e) {
            log.warn("API do Claude respondeu {}", e.statusCode());
        } catch (AnthropicIoException e) {
            log.warn("API do Claude fora de alcance: {}", e.getMessage());
        } catch (AnthropicException e) {
            log.warn("Resposta inesperada da API do Claude: {}", e.getMessage());
        }
        return faq.reply(snapshot, history);
    }

    MessageCreateParams params(Snapshot snapshot, List<Turn> history) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(model)
                .maxTokens(16000L)
                // Chat curto: esforço baixo responde mais rápido e gasta menos.
                .outputConfig(BetaOutputConfig.builder().effort(BetaOutputConfig.Effort.LOW).build())
                // Se o modelo recusar por engano, a API tenta outro modelo antes de devolver a recusa.
                .addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)
                .fallbacksDefault()
                .system(SYSTEM + "\n" + account(snapshot));
        for (Turn turn : merged(history)) {
            if (turn.role() == Role.USER) {
                builder.addUserMessage(turn.text());
            } else {
                builder.addAssistantMessage(turn.text());
            }
        }
        return builder.build();
    }

    static String account(Snapshot snapshot) {
        StringBuilder text = new StringBuilder("Dados da conta (somente leitura):\n")
                .append("- Primeiro nome: ").append(snapshot.firstName()).append('\n')
                .append("- Disponível na conta: ").append(Notifier.money(snapshot.availableCents())).append('\n')
                .append("- Nas caixinhas: ").append(Notifier.money(snapshot.vaultCents())).append('\n')
                .append("- Últimos lançamentos:");
        if (snapshot.recent().isEmpty()) {
            text.append(" nenhum");
        }
        for (Line line : snapshot.recent()) {
            text.append("\n  - ").append(DAY.format(line.at())).append(' ').append(line.description()).append(": ")
                    .append(line.direction().equals("CREDIT") ? "+" : "-").append(Notifier.money(line.amountCents()));
        }
        return text.toString();
    }

    /** A conversa precisa começar no cliente; mensagens seguidas do mesmo lado viram uma só. */
    static List<Turn> merged(List<Turn> history) {
        List<Turn> out = new ArrayList<>();
        for (Turn turn : history) {
            if (out.isEmpty() && turn.role() != Role.USER) {
                continue;
            }
            if (!out.isEmpty() && out.getLast().role() == turn.role()) {
                out.set(out.size() - 1, new Turn(turn.role(), out.getLast().text() + "\n\n" + turn.text()));
            } else {
                out.add(turn);
            }
        }
        return out;
    }
}
