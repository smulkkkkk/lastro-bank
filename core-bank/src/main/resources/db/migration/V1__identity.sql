CREATE TABLE core.app_user (
    id            uuid        PRIMARY KEY,
    full_name     text        NOT NULL CHECK (length(full_name) BETWEEN 3 AND 120),
    email         text        NOT NULL CHECK (email = lower(email)),
    cpf           char(11)    NOT NULL CHECK (cpf ~ '^[0-9]{11}$'),
    password_hash text        NOT NULL,
    role          text        NOT NULL DEFAULT 'CUSTOMER' CHECK (role IN ('CUSTOMER', 'ANALYST')),
    failed_logins smallint    NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
    locked_until  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT app_user_email_key UNIQUE (email),
    CONSTRAINT app_user_cpf_key UNIQUE (cpf)
);

-- Guarda só o hash: quem ler esta tabela não consegue usar os tokens.
CREATE TABLE core.refresh_token (
    id         uuid        PRIMARY KEY,
    family_id  uuid        NOT NULL,
    user_id    uuid        NOT NULL REFERENCES core.app_user (id),
    token_hash bytea       NOT NULL,
    issued_at  timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    used_at    timestamptz,
    revoked_at timestamptz,
    CONSTRAINT refresh_token_hash_key UNIQUE (token_hash),
    CHECK (expires_at > issued_at)
);

CREATE INDEX refresh_token_family_idx ON core.refresh_token (family_id);
CREATE INDEX refresh_token_user_idx ON core.refresh_token (user_id);
