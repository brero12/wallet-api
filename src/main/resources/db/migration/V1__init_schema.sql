CREATE TABLE accounts (
                          id             uuid PRIMARY KEY,
                          player_id      text        NOT NULL,
                          currency       text        NOT NULL,
                          type           text        NOT NULL
                              CHECK (type IN ('PLAYER_WALLET', 'SYSTEM_SINK', 'SYSTEM_FAUCET', 'MERCHANT')),
                          balance        bigint      NOT NULL DEFAULT 0,
                          allow_negative boolean     NOT NULL DEFAULT false,
                          version        bigint      NOT NULL DEFAULT 0,
                          created_at     timestamptz NOT NULL DEFAULT now(),
                          UNIQUE (player_id, currency, type),
                          CONSTRAINT balance_non_negative CHECK (allow_negative OR balance >= 0)
);

CREATE TABLE transactions (
                              id              uuid PRIMARY KEY,
                              idempotency_key text        NOT NULL UNIQUE,
                              request_hash    text        NOT NULL,   -- detects same key reused with a different payload
                              type            text        NOT NULL
                                  CHECK (type IN ('GRANT', 'PURCHASE', 'TRANSFER')),
                              game_id         text,
                              region          text,
                              metadata        jsonb       NOT NULL DEFAULT '{}'::jsonb,
                              created_at      timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE ledger_entries (
                                id          bigserial PRIMARY KEY,
                                txn_id      uuid        NOT NULL REFERENCES transactions (id),
                                account_id  uuid        NOT NULL REFERENCES accounts (id),
                                amount      bigint      NOT NULL CHECK (amount <> 0),  -- signed: credit +, debit -
                                created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_entries_account_time ON ledger_entries (account_id, id DESC);
CREATE INDEX idx_entries_txn ON ledger_entries (txn_id);

CREATE TABLE outbox (
                        id           bigserial PRIMARY KEY,
                        aggregate_id text        NOT NULL,   -- used as the Kafka message key
                        topic        text        NOT NULL,
                        payload      jsonb       NOT NULL,
                        created_at   timestamptz NOT NULL DEFAULT now(),
                        published_at timestamptz
);

-- partial index: the relay only ever scans unpublished rows
CREATE INDEX idx_outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE fraud_flags (
                             id         bigserial PRIMARY KEY,
                             account_id text        NOT NULL,
                             rule       text        NOT NULL,
                             score      int         NOT NULL,
                             evidence   jsonb       NOT NULL DEFAULT '{}'::jsonb,
                             created_at timestamptz NOT NULL DEFAULT now()
);