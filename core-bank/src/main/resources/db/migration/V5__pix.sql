-- Transferências internas no estilo Pix. Não há ligação com o Pix real.

-- Cada lado vê o próprio texto: quem paga lê "Pix para", quem recebe lê "Pix de".
ALTER TABLE core.ledger_entry ADD COLUMN description text CHECK (length(description) BETWEEN 1 AND 140);

CREATE OR REPLACE FUNCTION core.account_statement(
    p_account uuid, p_from timestamptz, p_to timestamptz, p_kind text, p_before bigint, p_limit integer)
RETURNS TABLE (entry_id bigint, transaction_id uuid, kind text, description text, direction text,
               amount_cents bigint, balance_after_cents bigint, created_at timestamptz)
LANGUAGE sql STABLE AS $$
    SELECT s.*
      FROM (SELECT e.id, e.transaction_id, t.kind, coalesce(e.description, t.description), e.direction, e.amount_cents,
                   (sum(CASE e.direction WHEN 'CREDIT' THEN e.amount_cents ELSE -e.amount_cents END)
                       OVER (ORDER BY e.id))::bigint,
                   e.created_at
              FROM core.ledger_entry e
              JOIN core.ledger_transaction t ON t.id = e.transaction_id
             WHERE e.account_id = p_account)
           AS s (id, transaction_id, kind, description, direction, amount_cents, balance_after, created_at)
     WHERE (p_from IS NULL OR s.created_at >= p_from)
       AND (p_to IS NULL OR s.created_at < p_to)
       AND (p_kind IS NULL OR s.kind = p_kind)
       AND (p_before IS NULL OR s.id < p_before)
     ORDER BY s.id DESC
     LIMIT p_limit
$$;

CREATE TABLE core.pix_key (
    id         uuid        PRIMARY KEY,
    account_id uuid        NOT NULL REFERENCES core.account (id),
    kind       text        NOT NULL CHECK (kind IN ('EMAIL', 'CPF', 'RANDOM')),
    value      text        NOT NULL CHECK (length(value) BETWEEN 3 AND 254),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pix_key_value_key UNIQUE (value)
);
CREATE INDEX pix_key_account_idx ON core.pix_key (account_id);

CREATE TABLE core.pix_transfer (
    id              uuid        PRIMARY KEY,
    from_account    uuid        NOT NULL REFERENCES core.account (id),
    to_account      uuid        NOT NULL REFERENCES core.account (id),
    key_used        text        NOT NULL,
    recipient_name  text        NOT NULL,
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    message         text        CHECK (length(message) BETWEEN 1 AND 80),
    idempotency_key text        NOT NULL,
    transaction_id  uuid        NOT NULL UNIQUE REFERENCES core.ledger_transaction (id),
    created_at      timestamptz NOT NULL,
    CONSTRAINT pix_transfer_idempotency_key UNIQUE (from_account, idempotency_key),
    CHECK (from_account <> to_account)
);
CREATE INDEX pix_transfer_sender_idx ON core.pix_transfer (from_account, created_at);

-- Quanto já saiu hoje e, entre 20h e 6h, quanto já saiu nesta noite. Horário de São Paulo.
CREATE FUNCTION core.pix_usage(p_account uuid, p_at timestamptz)
RETURNS TABLE (day_cents bigint, night_cents bigint, is_night boolean) LANGUAGE sql STABLE AS $$
    WITH local AS (SELECT p_at AT TIME ZONE 'America/Sao_Paulo' AS t),
    marks AS (
        SELECT t::time >= '20:00' OR t::time < '06:00' AS is_night,
               date_trunc('day', t) AT TIME ZONE 'America/Sao_Paulo' AS day_start,
               (date_trunc('day', t) + CASE WHEN t::time >= '20:00' THEN interval '20 hours'
                                            ELSE interval '-4 hours' END)
                   AT TIME ZONE 'America/Sao_Paulo' AS night_start
          FROM local)
    SELECT coalesce(sum(p.amount_cents) FILTER (WHERE p.created_at >= m.day_start), 0)::bigint,
           coalesce(sum(p.amount_cents) FILTER (WHERE m.is_night AND p.created_at >= m.night_start), 0)::bigint,
           m.is_night
      FROM marks m
      LEFT JOIN core.pix_transfer p
             ON p.from_account = p_account AND p.created_at <= p_at
            AND p.created_at >= least(m.day_start, m.night_start)
     GROUP BY m.is_night
$$;

-- Rastro: o primeiro dinheiro a entrar é o primeiro a sair.
-- Cada lançamento ocupa uma faixa na soma acumulada do seu lado (entradas ou saídas). Onde a faixa
-- de uma saída cruza a de uma entrada, aquele pedaço da saída veio daquela entrada. Serve nos dois
-- sentidos: de onde veio uma saída, e para onde foi uma entrada.
CREATE FUNCTION core.trace_entry(p_entry bigint)
RETURNS TABLE (entry_id bigint, kind text, description text, created_at timestamptz,
               amount_cents bigint, part_cents bigint) LANGUAGE sql STABLE AS $$
    WITH target AS (
        SELECT e.account_id, e.direction, e.amount_cents,
               (SELECT coalesce(sum(b.amount_cents), 0) FROM core.ledger_entry b
                 WHERE b.account_id = e.account_id AND b.direction = e.direction AND b.id < e.id) AS low
          FROM core.ledger_entry e WHERE e.id = p_entry),
    opposite AS (
        SELECT e.id, e.transaction_id, e.description, e.created_at, e.amount_cents,
               sum(e.amount_cents) OVER (ORDER BY e.id) AS high
          FROM core.ledger_entry e JOIN target t ON e.account_id = t.account_id AND e.direction <> t.direction)
    SELECT o.id, x.kind, coalesce(o.description, x.description), o.created_at, o.amount_cents,
           (least(o.high, t.low + t.amount_cents) - greatest(o.high - o.amount_cents, t.low))::bigint
      FROM opposite o
      JOIN core.ledger_transaction x ON x.id = o.transaction_id
      CROSS JOIN target t
     WHERE o.high > t.low AND o.high - o.amount_cents < t.low + t.amount_cents
     ORDER BY o.id
$$;
