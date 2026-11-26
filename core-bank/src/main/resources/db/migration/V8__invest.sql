-- Investimentos com cotação real e dinheiro fictício. Os reais passam pelo razão; as unidades de
-- cada ativo ficam em posições, porque não são reais.

INSERT INTO core.account (id, kind, branch, number) VALUES
    ('00000000-0000-0000-0000-000000000004', 'SYSTEM', '0000', 'BROKER');

ALTER TABLE core.ledger_transaction DROP CONSTRAINT ledger_transaction_kind_check;
ALTER TABLE core.ledger_transaction ADD CONSTRAINT ledger_transaction_kind_check
    CHECK (kind IN ('WELCOME', 'DEPOSIT', 'TRANSFER', 'VAULT_IN', 'VAULT_OUT', 'YIELD', 'CARD_PURCHASE',
                    'INVOICE_PAYMENT', 'CREDIT_CONVERSION', 'LOAN', 'LOAN_PAYMENT',
                    'INVESTMENT_BUY', 'INVESTMENT_SELL', 'REVERSAL'));

-- Custo médio: comprar soma custo; vender tira a parte proporcional.
CREATE TABLE core.position (
    owner_id   uuid           NOT NULL REFERENCES core.app_user (id),
    asset      text           NOT NULL CHECK (asset IN ('BTC', 'ETH', 'SOL', 'USD', 'EUR')),
    quantity   numeric(30, 8) NOT NULL CHECK (quantity >= 0),
    cost_cents bigint         NOT NULL CHECK (cost_cents >= 0),
    PRIMARY KEY (owner_id, asset),
    CHECK (quantity > 0 OR cost_cents = 0)
);

CREATE TABLE core.trade (
    id              uuid           PRIMARY KEY,
    owner_id        uuid           NOT NULL REFERENCES core.app_user (id),
    asset           text           NOT NULL,
    side            text           NOT NULL CHECK (side IN ('BUY', 'SELL')),
    quantity        numeric(30, 8) NOT NULL CHECK (quantity > 0),
    price           numeric(20, 8) NOT NULL CHECK (price > 0),
    amount_cents    bigint         NOT NULL CHECK (amount_cents > 0),
    -- O que foi pedido, para reconhecer o mesmo pedido repetido.
    requested_cents bigint,
    sell_all        boolean        NOT NULL DEFAULT false,
    idempotency_key text           NOT NULL,
    transaction_id  uuid           NOT NULL UNIQUE REFERENCES core.ledger_transaction (id),
    created_at      timestamptz    NOT NULL,
    CONSTRAINT trade_idempotency_key UNIQUE (owner_id, idempotency_key)
);
CREATE INDEX trade_owner_idx ON core.trade (owner_id, created_at DESC);
