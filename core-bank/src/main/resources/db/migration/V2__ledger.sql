-- Razão contábil em partidas dobradas. As regras que protegem o dinheiro vivem aqui,
-- para valerem também se a aplicação tiver um erro.

CREATE SEQUENCE core.account_number_seq START 100000;

CREATE TABLE core.account (
    id            uuid        PRIMARY KEY,
    owner_id      uuid        REFERENCES core.app_user (id),
    kind          text        NOT NULL CHECK (kind IN ('CHECKING', 'VAULT', 'SYSTEM')),
    branch        char(4)     NOT NULL DEFAULT '0001',
    number        text        NOT NULL,
    balance_cents bigint      NOT NULL DEFAULT 0,
    held_cents    bigint      NOT NULL DEFAULT 0,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT account_number_key UNIQUE (branch, number),
    CONSTRAINT account_owner_check CHECK ((kind = 'SYSTEM') = (owner_id IS NULL)),
    CONSTRAINT account_balance_check
        CHECK (kind = 'SYSTEM' OR (held_cents >= 0 AND held_cents <= balance_cents))
);

CREATE UNIQUE INDEX account_one_checking_per_owner ON core.account (owner_id) WHERE kind = 'CHECKING';
CREATE INDEX account_owner_idx ON core.account (owner_id);

CREATE TABLE core.ledger_transaction (
    id           uuid        PRIMARY KEY,
    kind         text        NOT NULL CHECK (kind IN ('WELCOME', 'DEPOSIT', 'TRANSFER', 'VAULT_IN',
                                                      'VAULT_OUT', 'CARD_PURCHASE', 'REVERSAL')),
    description  text        NOT NULL CHECK (length(description) BETWEEN 1 AND 140),
    external_ref text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    xact_id      xid8        NOT NULL DEFAULT pg_current_xact_id(),
    CONSTRAINT ledger_transaction_external_ref_key UNIQUE (external_ref)
);

CREATE TABLE core.ledger_entry (
    id             bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    transaction_id uuid        NOT NULL REFERENCES core.ledger_transaction (id),
    account_id     uuid        NOT NULL REFERENCES core.account (id),
    direction      text        NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_cents   bigint      NOT NULL CHECK (amount_cents > 0),
    created_at     timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX ledger_entry_account_idx ON core.ledger_entry (account_id, id);
CREATE INDEX ledger_entry_transaction_idx ON core.ledger_entry (transaction_id);

-- 1. Lançamentos são imutáveis.
CREATE FUNCTION core.reject_ledger_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'ledger is append-only: % on % is not allowed', TG_OP, TG_TABLE_NAME
        USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER ledger_transaction_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
    ON core.ledger_transaction FOR EACH STATEMENT EXECUTE FUNCTION core.reject_ledger_change();
CREATE TRIGGER ledger_entry_immutable BEFORE UPDATE OR DELETE OR TRUNCATE
    ON core.ledger_entry FOR EACH STATEMENT EXECUTE FUNCTION core.reject_ledger_change();

-- 2. Uma transação contábil já confirmada não recebe novos lançamentos.
CREATE FUNCTION core.reject_late_entry() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM core.ledger_transaction t
                   WHERE t.id = NEW.transaction_id AND t.xact_id = pg_current_xact_id()) THEN
        RAISE EXCEPTION 'ledger transaction % is closed', NEW.transaction_id
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER ledger_entry_open_transaction BEFORE INSERT
    ON core.ledger_entry FOR EACH ROW EXECUTE FUNCTION core.reject_late_entry();

-- 3. No commit, débitos e créditos têm que ser iguais, e não pode haver transação vazia.
CREATE FUNCTION core.check_ledger_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    v_transaction uuid;
    v_debits      numeric;
    v_credits     numeric;
BEGIN
    IF TG_TABLE_NAME = 'ledger_entry' THEN
        v_transaction := NEW.transaction_id;
    ELSE
        v_transaction := NEW.id;
    END IF;

    SELECT coalesce(sum(amount_cents) FILTER (WHERE direction = 'DEBIT'), 0),
           coalesce(sum(amount_cents) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO v_debits, v_credits
      FROM core.ledger_entry WHERE transaction_id = v_transaction;

    IF v_debits <> v_credits OR v_debits = 0 THEN
        RAISE EXCEPTION 'ledger transaction % is unbalanced: debits=% credits=%',
            v_transaction, v_debits, v_credits USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER ledger_transaction_balanced AFTER INSERT ON core.ledger_transaction
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION core.check_ledger_balanced();
CREATE CONSTRAINT TRIGGER ledger_entry_balanced AFTER INSERT ON core.ledger_entry
    DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION core.check_ledger_balanced();

-- 4. O saldo só muda por meio de um lançamento.
CREATE FUNCTION core.apply_entry_to_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE core.account
       SET balance_cents = balance_cents
           + CASE NEW.direction WHEN 'CREDIT' THEN NEW.amount_cents ELSE -NEW.amount_cents END
     WHERE id = NEW.account_id;
    RETURN NULL;
END $$;

CREATE TRIGGER ledger_entry_apply_balance AFTER INSERT
    ON core.ledger_entry FOR EACH ROW EXECUTE FUNCTION core.apply_entry_to_balance();

CREATE FUNCTION core.reject_direct_balance_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF pg_trigger_depth() < 2 THEN
        RAISE EXCEPTION 'balance_cents can only change through ledger_entry'
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER account_balance_guard BEFORE UPDATE OF balance_cents ON core.account
    FOR EACH ROW WHEN (OLD.balance_cents IS DISTINCT FROM NEW.balance_cents)
    EXECUTE FUNCTION core.reject_direct_balance_change();

CREATE FUNCTION core.reject_opening_balance() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'accounts must open with zero balance' USING ERRCODE = 'restrict_violation';
END $$;

CREATE TRIGGER account_opens_empty BEFORE INSERT ON core.account
    FOR EACH ROW WHEN (NEW.balance_cents <> 0 OR NEW.held_cents <> 0)
    EXECUTE FUNCTION core.reject_opening_balance();

-- 5. Conferência: precisa devolver sempre zero linhas.
CREATE VIEW core.v_reconciliation AS
SELECT a.id AS account_id, a.balance_cents, e.ledger_cents, a.balance_cents - e.ledger_cents AS difference_cents
  FROM core.account a
  CROSS JOIN LATERAL (
      SELECT coalesce(sum(CASE direction WHEN 'CREDIT' THEN amount_cents ELSE -amount_cents END), 0) AS ledger_cents
        FROM core.ledger_entry WHERE account_id = a.id) e
 WHERE a.balance_cents <> e.ledger_cents;

-- 6. Extrato. O saldo acumulado é calculado antes dos filtros, senão mudaria conforme a busca.
CREATE FUNCTION core.account_statement(
    p_account uuid, p_from timestamptz, p_to timestamptz, p_kind text, p_before bigint, p_limit integer)
RETURNS TABLE (entry_id bigint, transaction_id uuid, kind text, description text, direction text,
               amount_cents bigint, balance_after_cents bigint, created_at timestamptz)
LANGUAGE sql STABLE AS $$
    SELECT s.*
      FROM (SELECT e.id, e.transaction_id, t.kind, t.description, e.direction, e.amount_cents,
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

-- Contas internas: origem do dinheiro fictício e destino das compras simuladas.
INSERT INTO core.account (id, kind, branch, number) VALUES
    ('00000000-0000-0000-0000-000000000001', 'SYSTEM', '0000', 'FUNDING'),
    ('00000000-0000-0000-0000-000000000002', 'SYSTEM', '0000', 'MERCHANTS');
