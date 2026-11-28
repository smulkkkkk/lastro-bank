-- Open Finance simulado: bancos fictícios, consentimento com prazo e revogação, e dinheiro trazido
-- de lá para cá. E a aproximação passa a dizer com qual aparelho foi.

ALTER TABLE core.ledger_transaction DROP CONSTRAINT ledger_transaction_kind_check;
ALTER TABLE core.ledger_transaction ADD CONSTRAINT ledger_transaction_kind_check
    CHECK (kind IN ('WELCOME', 'DEPOSIT', 'TRANSFER', 'VAULT_IN', 'VAULT_OUT', 'YIELD', 'CARD_PURCHASE',
                    'INVOICE_PAYMENT', 'CREDIT_CONVERSION', 'LOAN', 'LOAN_PAYMENT',
                    'INVESTMENT_BUY', 'INVESTMENT_SELL', 'BILL_PAYMENT', 'TOP_UP', 'REWARD',
                    'OPEN_FINANCE_IN', 'REVERSAL'));

-- Com qual aparelho a compra foi aproximada. Vazio quando não foi por aproximação.
ALTER TABLE core.purchase
    ADD COLUMN device text CHECK (device IN ('CARD', 'PHONE', 'WATCH')),
    ADD CONSTRAINT purchase_device_contactless_check CHECK (device IS NULL OR contactless);

CREATE TABLE core.external_bank (
    code text PRIMARY KEY,
    name text NOT NULL
);
INSERT INTO core.external_bank VALUES
    ('CORAL', 'Banco Coral'),
    ('PEROLA', 'Banco Pérola'),
    ('MANGUE', 'Cooperativa Mangue');

-- Como no Open Finance: o cliente pede aqui, autoriza no outro banco, e pode revogar quando quiser.
CREATE TABLE core.consent (
    id            uuid        PRIMARY KEY,
    owner_id      uuid        NOT NULL REFERENCES core.app_user (id),
    bank_code     text        NOT NULL REFERENCES core.external_bank (code),
    permissions   text[]      NOT NULL CHECK (permissions <@ ARRAY['BALANCES', 'TRANSACTIONS', 'PAYMENTS']
                                              AND cardinality(permissions) > 0),
    status        text        NOT NULL CHECK (status IN ('AWAITING', 'AUTHORIZED', 'REJECTED', 'REVOKED')),
    months        smallint    NOT NULL CHECK (months IN (3, 6, 12)),
    created_at    timestamptz NOT NULL,
    authorized_at timestamptz,
    expires_at    timestamptz,
    ended_at      timestamptz,
    CONSTRAINT consent_authorized_check CHECK ((status = 'AUTHORIZED') <= (authorized_at IS NOT NULL AND expires_at IS NOT NULL))
);
-- Um consentimento vivo por banco: pedir de novo exige revogar o anterior.
CREATE UNIQUE INDEX consent_live_idx ON core.consent (owner_id, bank_code) WHERE status IN ('AWAITING', 'AUTHORIZED');

-- A conta do cliente no outro banco, como o outro banco informaria.
CREATE TABLE core.external_account (
    consent_id    uuid        PRIMARY KEY REFERENCES core.consent (id),
    branch        text        NOT NULL,
    number        text        NOT NULL,
    balance_cents bigint      NOT NULL CHECK (balance_cents >= 0),
    seed          bigint      NOT NULL
);

CREATE TABLE core.external_transfer (
    id              uuid        PRIMARY KEY,
    consent_id      uuid        NOT NULL REFERENCES core.consent (id),
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    idempotency_key text        NOT NULL,
    transaction_id  uuid        NOT NULL REFERENCES core.ledger_transaction (id),
    created_at      timestamptz NOT NULL,
    CONSTRAINT external_transfer_key UNIQUE (consent_id, idempotency_key)
);
