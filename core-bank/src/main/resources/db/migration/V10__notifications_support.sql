-- Avisos e atendimento saem pela mesma outbox do antifraude: gravados na transação que os causou,
-- entregues depois por um worker. Se o Pix foi liquidado, o aviso existe; se a transação desfez, não.

ALTER TABLE core.outbox DROP CONSTRAINT outbox_kind_check;
ALTER TABLE core.outbox
    ADD CONSTRAINT outbox_kind_check CHECK (kind IN ('PIX_ANALYSIS', 'NOTIFICATION', 'SUPPORT_HANDOFF')),
    ADD COLUMN payload jsonb,
    ADD CONSTRAINT outbox_payload_check CHECK ((kind = 'PIX_ANALYSIS') = (payload IS NULL));

-- Uma conversa com o Lino. Quando o cliente pede uma pessoa, ganha protocolo e vai para o WhatsApp.
CREATE TABLE core.support_conversation (
    id            uuid        PRIMARY KEY,
    user_id       uuid        NOT NULL REFERENCES core.app_user (id),
    status        text        NOT NULL CHECK (status IN ('OPEN', 'HANDED_OFF')),
    protocol      text        UNIQUE CHECK (protocol ~ '^LST-[0-9]{8}-[0-9A-Z]{6}$'),
    phone         text        CHECK (phone ~ '^[1-9][0-9]{9,10}$'),
    created_at    timestamptz NOT NULL,
    handed_off_at timestamptz,
    CONSTRAINT support_handoff_check
        CHECK ((status = 'HANDED_OFF') = (protocol IS NOT NULL AND phone IS NOT NULL AND handed_off_at IS NOT NULL))
);
-- No máximo uma conversa aberta por cliente.
CREATE UNIQUE INDEX support_conversation_open_idx ON core.support_conversation (user_id) WHERE status = 'OPEN';
CREATE INDEX support_conversation_user_idx ON core.support_conversation (user_id, created_at DESC);

CREATE TABLE core.support_message (
    id              bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    conversation_id uuid        NOT NULL REFERENCES core.support_conversation (id),
    role            text        NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    text            text        NOT NULL CHECK (length(text) BETWEEN 1 AND 4000),
    -- Quem respondeu: CLAUDE ou FAQ (sem chave de IA, ou com a IA fora do ar).
    source          text        CHECK (source IN ('CLAUDE', 'FAQ')),
    created_at      timestamptz NOT NULL,
    CONSTRAINT support_message_role_source_check CHECK ((role = 'ASSISTANT') = (source IS NOT NULL))
);
CREATE INDEX support_message_conversation_idx ON core.support_message (conversation_id, id);
