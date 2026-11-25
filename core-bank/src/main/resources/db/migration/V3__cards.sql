-- Cartões fictícios. Só os 4 últimos dígitos existem: não há número completo para vazar.
CREATE TABLE core.card (
    id          uuid        PRIMARY KEY,
    account_id  uuid        NOT NULL REFERENCES core.account (id),
    position    smallint    NOT NULL CHECK (position BETWEEN 1 AND 3),
    design      text        NOT NULL CHECK (design IN ('ONIX', 'MARE', 'BRASA')),
    label       text        NOT NULL CHECK (length(label) BETWEEN 1 AND 30),
    last4       char(4)     NOT NULL CHECK (last4 ~ '^[0-9]{4}$'),
    is_primary  boolean     NOT NULL DEFAULT false,
    frozen      boolean     NOT NULL DEFAULT false,
    contactless boolean     NOT NULL DEFAULT true,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT card_position_key UNIQUE (account_id, position)
);

CREATE UNIQUE INDEX card_one_primary_per_account ON core.card (account_id) WHERE is_primary;

CREATE FUNCTION core.issue_cards(p_account uuid) RETURNS void LANGUAGE sql AS $$
    INSERT INTO core.card (id, account_id, position, design, label, last4, is_primary)
    SELECT gen_random_uuid(), p_account, v.position, v.design, v.label,
           lpad(floor(random() * 10000)::int::text, 4, '0'), v.position = 1
      FROM (VALUES (1, 'ONIX', 'Principal'), (2, 'MARE', 'Compras online'), (3, 'BRASA', 'Assinaturas'))
           AS v (position, design, label)
$$;

-- Contas abertas antes desta versão também recebem os seus.
SELECT core.issue_cards(id) FROM core.account WHERE kind = 'CHECKING';
