-- O Pix passa a esperar a análise do sentinel: reserva o dinheiro, analisa, e só então lança no razão.

ALTER TABLE core.pix_transfer
    ADD COLUMN status text NOT NULL DEFAULT 'SETTLED'
        CHECK (status IN ('PENDING', 'UNDER_REVIEW', 'SETTLED', 'DENIED', 'CANCELLED')),
    ADD COLUMN score smallint CHECK (score BETWEEN 0 AND 100),
    ADD COLUMN reasons jsonb,
    ADD COLUMN decided_at timestamptz,
    ALTER COLUMN transaction_id DROP NOT NULL,
    ADD CONSTRAINT pix_transfer_settled_check CHECK ((status = 'SETTLED') = (transaction_id IS NOT NULL));
ALTER TABLE core.pix_transfer ALTER COLUMN status DROP DEFAULT;
CREATE INDEX pix_transfer_open_idx ON core.pix_transfer (status, created_at) WHERE status IN ('PENDING', 'UNDER_REVIEW');

-- Gravado na mesma transação do Pix: se o Pix existe, o pedido de análise também existe.
CREATE TABLE core.outbox (
    id            uuid        PRIMARY KEY,
    kind          text        NOT NULL CHECK (kind IN ('PIX_ANALYSIS')),
    aggregate_id  uuid        NOT NULL,
    attempts      integer     NOT NULL DEFAULT 0,
    available_at  timestamptz NOT NULL,
    claimed_until timestamptz,
    created_at    timestamptz NOT NULL,
    CONSTRAINT outbox_aggregate_key UNIQUE (kind, aggregate_id)
);
CREATE INDEX outbox_ready_idx ON core.outbox (available_at);

CREATE TABLE core.pix_review (
    transfer_id uuid        PRIMARY KEY REFERENCES core.pix_transfer (id),
    analyst_id  uuid        NOT NULL REFERENCES core.app_user (id),
    decision    text        NOT NULL CHECK (decision IN ('APPROVED', 'DENIED')),
    reason      text        NOT NULL CHECK (length(reason) BETWEEN 5 AND 280),
    decided_at  timestamptz NOT NULL
);

-- O limite conta o que está esperando análise; recusado e cancelado não contam.
CREATE OR REPLACE FUNCTION core.pix_usage(p_account uuid, p_at timestamptz)
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
            AND p.status IN ('PENDING', 'UNDER_REVIEW', 'SETTLED')
     GROUP BY m.is_night
$$;
