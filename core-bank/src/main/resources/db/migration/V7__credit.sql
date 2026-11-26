-- Crédito e empréstimo. O que o cliente deve é o saldo negativo de uma conta própria no razão.

ALTER TABLE core.account DROP CONSTRAINT account_kind_check;
ALTER TABLE core.account ADD CONSTRAINT account_kind_check
    CHECK (kind IN ('CHECKING', 'VAULT', 'CREDIT', 'LOAN', 'SYSTEM'));
ALTER TABLE core.account ADD COLUMN credit_limit_cents bigint NOT NULL DEFAULT 0 CHECK (credit_limit_cents >= 0);

-- Dívida nunca passa do limite e nunca vira saldo a favor.
ALTER TABLE core.account DROP CONSTRAINT account_balance_check;
ALTER TABLE core.account ADD CONSTRAINT account_balance_check CHECK (
    CASE WHEN kind = 'SYSTEM' THEN true
         WHEN kind IN ('CREDIT', 'LOAN') THEN held_cents = 0 AND balance_cents <= 0
                                              AND balance_cents >= -credit_limit_cents
         ELSE held_cents >= 0 AND held_cents <= balance_cents AND credit_limit_cents = 0 END);

CREATE UNIQUE INDEX account_one_credit_per_owner ON core.account (owner_id) WHERE kind = 'CREDIT';

ALTER TABLE core.ledger_transaction DROP CONSTRAINT ledger_transaction_kind_check;
ALTER TABLE core.ledger_transaction ADD CONSTRAINT ledger_transaction_kind_check
    CHECK (kind IN ('WELCOME', 'DEPOSIT', 'TRANSFER', 'VAULT_IN', 'VAULT_OUT', 'YIELD', 'CARD_PURCHASE',
                    'INVOICE_PAYMENT', 'CREDIT_CONVERSION', 'LOAN', 'LOAN_PAYMENT', 'REVERSAL'));

INSERT INTO core.account (id, owner_id, kind, number, credit_limit_cents)
SELECT gen_random_uuid(), owner_id, 'CREDIT', 'CR-' || nextval('core.account_number_seq'), 300000
  FROM core.account WHERE kind = 'CHECKING';

CREATE TABLE core.purchase (
    id              uuid        PRIMARY KEY,
    owner_id        uuid        NOT NULL REFERENCES core.app_user (id),
    card_id         uuid        REFERENCES core.card (id),
    kind            text        NOT NULL CHECK (kind IN ('PURCHASE', 'CONVERSION')),
    mode            text        NOT NULL CHECK (mode IN ('DEBIT', 'CREDIT')),
    merchant        text        NOT NULL,
    category        text        NOT NULL,
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    installments    smallint    NOT NULL CHECK (installments BETWEEN 1 AND 12),
    contactless     boolean     NOT NULL DEFAULT false,
    idempotency_key text        NOT NULL,
    transaction_id  uuid        NOT NULL UNIQUE REFERENCES core.ledger_transaction (id),
    created_at      timestamptz NOT NULL,
    CONSTRAINT purchase_idempotency_key UNIQUE (owner_id, idempotency_key),
    CHECK (kind = 'CONVERSION' OR card_id IS NOT NULL),
    CHECK (mode = 'CREDIT' OR installments = 1)
);
CREATE INDEX purchase_owner_idx ON core.purchase (owner_id, created_at DESC);

-- A fatura não é guardada: é a soma das parcelas de um mês menos o que já foi pago dele.
CREATE TABLE core.installment (
    purchase_id  uuid     NOT NULL REFERENCES core.purchase (id),
    number       smallint NOT NULL CHECK (number >= 1),
    owner_id     uuid     NOT NULL REFERENCES core.app_user (id),
    reference    date     NOT NULL CHECK (extract(day FROM reference) = 1),
    amount_cents bigint   NOT NULL CHECK (amount_cents > 0),
    PRIMARY KEY (purchase_id, number)
);
CREATE INDEX installment_invoice_idx ON core.installment (owner_id, reference);

CREATE TABLE core.invoice_payment (
    id             uuid        PRIMARY KEY,
    owner_id       uuid        NOT NULL REFERENCES core.app_user (id),
    reference      date        NOT NULL,
    amount_cents   bigint      NOT NULL CHECK (amount_cents > 0),
    transaction_id uuid        NOT NULL UNIQUE REFERENCES core.ledger_transaction (id),
    created_at     timestamptz NOT NULL
);
CREATE INDEX invoice_payment_idx ON core.invoice_payment (owner_id, reference);

CREATE TABLE core.loan (
    account_id      uuid          PRIMARY KEY REFERENCES core.account (id),
    owner_id        uuid          NOT NULL REFERENCES core.app_user (id),
    principal_cents bigint        NOT NULL CHECK (principal_cents > 0),
    monthly_percent numeric(6, 4) NOT NULL CHECK (monthly_percent > 0),
    installments    smallint      NOT NULL CHECK (installments BETWEEN 1 AND 60),
    idempotency_key text          NOT NULL,
    created_on      date          NOT NULL,
    settled_on      date,
    CONSTRAINT loan_idempotency_key UNIQUE (owner_id, idempotency_key)
);
CREATE UNIQUE INDEX loan_one_active_per_owner ON core.loan (owner_id) WHERE settled_on IS NULL;

CREATE TABLE core.loan_installment (
    loan_id         uuid     NOT NULL REFERENCES core.loan (account_id),
    number          smallint NOT NULL CHECK (number >= 1),
    due_on          date     NOT NULL,
    principal_cents bigint   NOT NULL CHECK (principal_cents > 0),
    interest_cents  bigint   NOT NULL CHECK (interest_cents >= 0),
    paid_on         date,
    paid_cents      bigint,
    PRIMARY KEY (loan_id, number),
    CHECK ((paid_on IS NULL) = (paid_cents IS NULL))
);
