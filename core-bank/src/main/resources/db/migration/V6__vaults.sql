-- Caixinhas que rendem um percentual do CDI.

ALTER TABLE core.ledger_transaction DROP CONSTRAINT ledger_transaction_kind_check;
ALTER TABLE core.ledger_transaction ADD CONSTRAINT ledger_transaction_kind_check
    CHECK (kind IN ('WELCOME', 'DEPOSIT', 'TRANSFER', 'VAULT_IN', 'VAULT_OUT', 'YIELD', 'CARD_PURCHASE', 'REVERSAL'));

INSERT INTO core.account (id, kind, branch, number) VALUES
    ('00000000-0000-0000-0000-000000000003', 'SYSTEM', '0000', 'INTEREST');

-- O dinheiro fica em uma conta VAULT do razão; aqui fica só o que é próprio da caixinha.
CREATE TABLE core.vault (
    account_id      uuid           PRIMARY KEY REFERENCES core.account (id),
    owner_id        uuid           NOT NULL REFERENCES core.app_user (id),
    name            text           NOT NULL CHECK (length(name) BETWEEN 1 AND 40),
    goal_cents      bigint         CHECK (goal_cents > 0),
    cdi_percent     smallint       NOT NULL CHECK (cdi_percent BETWEEN 1 AND 200),
    locked_until    date,
    -- Fração de centavo que ainda não virou dinheiro. Sem ela, saldo pequeno nunca renderia.
    yield_remainder numeric(20, 10) NOT NULL DEFAULT 0 CHECK (yield_remainder >= 0 AND yield_remainder < 1),
    created_on      date           NOT NULL,
    archived_on     date
);
CREATE INDEX vault_owner_idx ON core.vault (owner_id);

-- Taxa em % por dia útil, como o Banco Central publica (série 12). Sem linha, o dia não rende.
CREATE TABLE core.cdi_rate (
    day           date           PRIMARY KEY,
    daily_percent numeric(12, 8) NOT NULL CHECK (daily_percent > 0 AND daily_percent < 1)
);

-- Uma linha por caixinha e dia: é o que impede o mesmo dia de render duas vezes.
CREATE TABLE core.vault_yield (
    vault_id     uuid            NOT NULL REFERENCES core.vault (account_id),
    day          date            NOT NULL REFERENCES core.cdi_rate (day),
    base_cents   bigint          NOT NULL CHECK (base_cents >= 0),
    exact_cents  numeric(20, 10) NOT NULL,
    posted_cents bigint          NOT NULL CHECK (posted_cents >= 0),
    PRIMARY KEY (vault_id, day)
);
