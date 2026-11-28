-- Boleto e recarga de celular. O dinheiro sai para a conta de lojistas, como as compras no cartão.

ALTER TABLE core.ledger_transaction DROP CONSTRAINT ledger_transaction_kind_check;
ALTER TABLE core.ledger_transaction ADD CONSTRAINT ledger_transaction_kind_check
    CHECK (kind IN ('WELCOME', 'DEPOSIT', 'TRANSFER', 'VAULT_IN', 'VAULT_OUT', 'YIELD', 'CARD_PURCHASE',
                    'INVOICE_PAYMENT', 'CREDIT_CONVERSION', 'LOAN', 'LOAN_PAYMENT',
                    'INVESTMENT_BUY', 'INVESTMENT_SELL', 'BILL_PAYMENT', 'TOP_UP', 'REVERSAL'));

-- Faz o papel do registro central de boletos: o banco só paga boleto que o emissor registrou.
CREATE TABLE core.boleto (
    barcode      text        PRIMARY KEY CHECK (barcode ~ '^[0-9]{44}$'),
    beneficiary  text        NOT NULL CHECK (length(beneficiary) BETWEEN 2 AND 80),
    amount_cents bigint      NOT NULL CHECK (amount_cents > 0),
    due_date     date        NOT NULL,
    -- Quem gerou o boleto de teste. Qualquer cliente pode pagar.
    issued_for   uuid        REFERENCES core.app_user (id),
    created_at   timestamptz NOT NULL
);

CREATE TABLE core.bill_payment (
    id              uuid        PRIMARY KEY,
    owner_id        uuid        NOT NULL REFERENCES core.app_user (id),
    kind            text        NOT NULL CHECK (kind IN ('BOLETO', 'TOP_UP')),
    barcode         text        REFERENCES core.boleto (barcode),
    operator        text,
    phone           text        CHECK (phone ~ '^[1-9][0-9]9[0-9]{8}$'),
    amount_cents    bigint      NOT NULL CHECK (amount_cents > 0),
    description     text        NOT NULL,
    -- O que foi pedido, para reconhecer a mesma Idempotency-Key usada em outro pagamento.
    request         text        NOT NULL,
    idempotency_key text        NOT NULL,
    transaction_id  uuid        NOT NULL REFERENCES core.ledger_transaction (id),
    created_at      timestamptz NOT NULL,
    CONSTRAINT bill_payment_key UNIQUE (owner_id, idempotency_key),
    CONSTRAINT bill_payment_boleto_check CHECK ((kind = 'BOLETO') = (barcode IS NOT NULL)),
    CONSTRAINT bill_payment_top_up_check CHECK ((kind = 'TOP_UP') = (operator IS NOT NULL AND phone IS NOT NULL))
);
-- Um boleto é pago uma vez só, por quem quer que seja.
CREATE UNIQUE INDEX bill_payment_boleto_once ON core.bill_payment (barcode) WHERE barcode IS NOT NULL;
CREATE INDEX bill_payment_owner_idx ON core.bill_payment (owner_id, created_at DESC);
