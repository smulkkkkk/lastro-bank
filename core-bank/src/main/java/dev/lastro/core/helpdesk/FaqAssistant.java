package dev.lastro.core.helpdesk;

import dev.lastro.core.notify.Notifier;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Respostas prontas por palavra-chave. É o Lino quando não há chave da API do Claude, e o plano B
 * quando a API não responde.
 */
@Component
class FaqAssistant implements Assistant {

    private record Topic(List<String> words, String answer) {
    }

    private static final List<Topic> TOPICS = List.of(
            new Topic(List.of("senha", "codigo", "golpe", "token"),
                    "Nunca passe sua senha ou código para ninguém, nem para mim. O Lastro não pede senha por chat, telefone ou WhatsApp."),
            new Topic(List.of("atendente", "humano", "pessoa", "whatsapp", "reclama"),
                    "Posso chamar uma pessoa do time. Toque em \"Falar com atendente\" e informe seu WhatsApp: você recebe um protocolo na hora."),
            new Topic(List.of("limite", "noturno", "noite"),
                    "O Pix tem limite de R$ 5.000,00 por dia e R$ 2.000,00 entre 20h e 6h. Você acompanha o quanto já usou na tela do Pix."),
            new Topic(List.of("revisao", "analise", "barrado", "recusado", "nao enviado"),
                    "Todo Pix passa pelo antifraude. Se ficar em revisão, uma pessoa confere em até 24 horas e o valor fica reservado. Se for barrado, o dinheiro volta na hora para o seu saldo."),
            new Topic(List.of("pix", "chave", "transferi"),
                    "Para mandar um Pix, abra Pix, digite a chave de quem vai receber e o valor. Você cadastra suas chaves (CPF, e-mail ou aleatória) na mesma tela."),
            new Topic(List.of("caixinha", "cdi", "rende", "guardar"),
                    "As caixinhas rendem todo dia: a livre paga 100% do CDI e você tira quando quiser; a travada paga 110% do CDI até a data escolhida."),
            new Topic(List.of("fatura", "credito", "parcela"),
                    "No crédito, a fatura fecha dia 25 e vence dia 5. Compras podem ser parceladas em até 12 vezes, e você paga a fatura pelo saldo da conta."),
            new Topic(List.of("emprestimo", "emprestar"),
                    "O empréstimo usa a tabela Price, com juros de 2,49% ao mês. A simulação mostra a parcela antes de você contratar."),
            new Topic(List.of("investi", "bitcoin", "dolar", "euro", "cripto", "acao"),
                    "Em Investimentos você compra e vende Bitcoin, Ethereum, Solana, dólar e euro com cotação ao vivo, e acompanha o preço médio e o histórico."),
            new Topic(List.of("cartao", "bloque", "virtual", "aproxima"),
                    "Em Cartões você vê o cartão físico e o virtual, bloqueia e desbloqueia na hora e escolhe o acabamento laranja ou azul."));

    private static final String FALLBACK = "Sou o Lino e entendo de Pix, limites, caixinhas, cartões, crédito, empréstimo "
            + "e investimentos. Se for outra coisa, toque em \"Falar com atendente\" que eu chamo uma pessoa.";

    @Override
    public Reply reply(Snapshot snapshot, List<Turn> history) {
        String asked = plain(history.getLast().text());
        if (asked.contains("saldo")) {
            return new Reply("%s, você tem %s disponível na conta e %s nas caixinhas.".formatted(snapshot.firstName(),
                    Notifier.money(snapshot.availableCents()), Notifier.money(snapshot.vaultCents())), "FAQ");
        }
        for (Topic topic : TOPICS) {
            if (topic.words().stream().anyMatch(asked::contains)) {
                return new Reply(topic.answer(), "FAQ");
            }
        }
        if (history.size() == 1) {
            return new Reply("Oi, %s! %s".formatted(snapshot.firstName(), FALLBACK), "FAQ");
        }
        return new Reply(FALLBACK, "FAQ");
    }

    /** Sem acento e em minúsculas: "Cartão" e "cartao" contam igual. */
    static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
