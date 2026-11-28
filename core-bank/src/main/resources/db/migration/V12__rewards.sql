-- Bolhas: a moeda do app. Uma bolha vale R$ 0,01 e vem de cashback nas compras no cartão.
-- Trocar bolhas por saldo é um lançamento no razão, saindo da conta interna de recompensas.

INSERT INTO core.account (id, kind, branch, number) VALUES
    ('00000000-0000-0000-0000-000000000005', 'SYSTEM', '0000', 'REWARDS');

ALTER TABLE core.ledger_transaction DROP CONSTRAINT ledger_transaction_kind_check;
ALTER TABLE core.ledger_transaction ADD CONSTRAINT ledger_transaction_kind_check
    CHECK (kind IN ('WELCOME', 'DEPOSIT', 'TRANSFER', 'VAULT_IN', 'VAULT_OUT', 'YIELD', 'CARD_PURCHASE',
                    'INVOICE_PAYMENT', 'CREDIT_CONVERSION', 'LOAN', 'LOAN_PAYMENT',
                    'INVESTMENT_BUY', 'INVESTMENT_SELL', 'BILL_PAYMENT', 'TOP_UP', 'REWARD', 'REVERSAL'));

-- As lojas fictícias das compras no cartão viram parceiras. Percentuais em pontos-base: 500 = 5%.
CREATE TABLE core.store (
    merchant         text    PRIMARY KEY,
    cashback_bp      integer NOT NULL CHECK (cashback_bp BETWEEN 0 AND 2000),
    coupon_percent   integer NOT NULL CHECK (coupon_percent BETWEEN 1 AND 50),
    coupon_cap_cents bigint  NOT NULL CHECK (coupon_cap_cents > 0),
    coupon_min_cents bigint  NOT NULL CHECK (coupon_min_cents >= 0),
    blurb            text    NOT NULL
);
INSERT INTO core.store VALUES
    ('MARE_CHEIA', 300, 15, 1000, 3000, 'Pão quentinho de manhã e café de tarde.'),
    ('ARRECIFE', 200, 5, 2500, 15000, 'O mercado do bairro, com feira às quintas.'),
    ('CORRENTEZA', 100, 3, 1500, 10000, 'Combustível e lavagem, aberto 24 horas.'),
    ('FAROL', 800, 20, 3000, 5000, 'Livros novos, usados e um sofá para ler.'),
    ('ABISSAL', 500, 30, 2000, 4000, 'Sessões de cinema e pipoca no balde.'),
    ('ANCORA', 150, 10, 15000, 50000, 'Celulares, fones e tudo que liga na tomada.');

-- Extrato de bolhas, só de inclusão. O saldo é a soma.
CREATE TABLE core.bubble_entry (
    id          bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    owner_id    uuid        NOT NULL REFERENCES core.app_user (id),
    bubbles     bigint      NOT NULL CHECK (bubbles <> 0),
    reason      text        NOT NULL CHECK (reason IN ('CASHBACK', 'REDEEM')),
    -- A compra que deu o cashback, ou o lançamento da troca: o mesmo fato não conta duas vezes.
    ref         uuid        NOT NULL,
    description text        NOT NULL,
    created_at  timestamptz NOT NULL,
    CONSTRAINT bubble_entry_ref_key UNIQUE (reason, ref)
);
CREATE INDEX bubble_entry_owner_idx ON core.bubble_entry (owner_id, id DESC);
CREATE TRIGGER bubble_entry_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
    ON core.bubble_entry FOR EACH STATEMENT EXECUTE FUNCTION core.reject_ledger_change();

-- Cupom pego numa loja. Vale uma compra, por sete dias; depois de usado, dá para pegar outro.
CREATE TABLE core.coupon (
    id             uuid        PRIMARY KEY,
    owner_id       uuid        NOT NULL REFERENCES core.app_user (id),
    merchant       text        NOT NULL REFERENCES core.store (merchant),
    claimed_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    used_at        timestamptz,
    transaction_id uuid        REFERENCES core.ledger_transaction (id),
    CONSTRAINT coupon_used_check CHECK ((used_at IS NULL) = (transaction_id IS NULL))
);
CREATE UNIQUE INDEX coupon_open_idx ON core.coupon (owner_id, merchant) WHERE used_at IS NULL;

ALTER TABLE core.purchase ADD COLUMN discount_cents bigint NOT NULL DEFAULT 0 CHECK (discount_cents >= 0);
