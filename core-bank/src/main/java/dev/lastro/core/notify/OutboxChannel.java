package dev.lastro.core.notify;

import java.util.UUID;

/** Um destino da outbox: o sentinel para avisos, o n8n para atendimento. */
public interface OutboxChannel {

    /** O valor da coluna kind que este canal entrega. */
    String kind();

    /** Lança exceção se não entregou; o worker tenta de novo com espera crescente. */
    void deliver(UUID id, String payload);
}
