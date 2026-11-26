package dev.lastro.core.helpdesk;

import java.time.Instant;
import java.util.List;

/** Quem responde no chat. Só conversa: não tem ferramenta nenhuma para mexer em dinheiro. */
interface Assistant {

    enum Role { USER, ASSISTANT }

    record Turn(Role role, String text) {
    }

    /** source é CLAUDE ou FAQ, e vai gravado com a mensagem. */
    record Reply(String text, String source) {
    }

    record Line(String description, String direction, long amountCents, Instant at) {
    }

    /** O que o Lino pode saber da conta, só para leitura. */
    record Snapshot(String firstName, long availableCents, long vaultCents, List<Line> recent) {
    }

    /** history termina na mensagem do cliente que está sendo respondida. */
    Reply reply(Snapshot snapshot, List<Turn> history);
}
