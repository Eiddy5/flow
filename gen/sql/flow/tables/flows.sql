CREATE TABLE IF NOT EXISTS flows (
    id             varchar(64) NOT NULL,
    key            varchar(128) NOT NULL,
    company_id     varchar(64) NOT NULL,
    reversion      bigint NOT NULL,
    description    text NOT NULL DEFAULT '',

    creator        jsonb NOT NULL,
    creator_id     varchar(64)
        GENERATED ALWAYS AS (creator ->> 'id') STORED,
    updater        jsonb NOT NULL,
    updater_id     varchar(64)
        GENERATED ALWAYS AS (updater ->> 'id') STORED,
    deleter        jsonb,
    deleter_id     varchar(64)
        GENERATED ALWAYS AS (deleter ->> 'id') STORED,

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz,

    inputs         jsonb NOT NULL DEFAULT '[]'::jsonb,
    outputs        jsonb NOT NULL DEFAULT '[]'::jsonb,
    deleted        boolean NOT NULL DEFAULT false,

    CONSTRAINT pk_flows
        PRIMARY KEY (company_id, id, reversion),
    CONSTRAINT uq_flows_company_key_reversion
        UNIQUE (company_id, key, reversion),
    CONSTRAINT ck_flows_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_flows_key
        CHECK (length(btrim(key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flows_reversion
        CHECK (reversion > 0),
    CONSTRAINT ck_flows_inputs
        CHECK (jsonb_typeof(inputs) = 'array'),
    CONSTRAINT ck_flows_outputs
        CHECK (jsonb_typeof(outputs) = 'array'),
    CONSTRAINT ck_flows_creator
        CHECK (
            jsonb_typeof(creator) = 'object'
            AND creator ? 'id'
        ),
    CONSTRAINT ck_flows_updater
        CHECK (
            jsonb_typeof(updater) = 'object'
            AND updater ? 'id'
        ),
    CONSTRAINT ck_flows_deleter
        CHECK (
            deleter IS NULL
            OR (
                jsonb_typeof(deleter) = 'object'
                AND deleter ? 'id'
            )
        ),
    CONSTRAINT ck_flows_deleted
        CHECK (
            (
                deleted IS FALSE
                AND deleter IS NULL
                AND deleted_at IS NULL
            )
            OR (
                deleted IS TRUE
                AND deleter IS NOT NULL
                AND deleted_at IS NOT NULL
            )
        )
);

CREATE INDEX IF NOT EXISTS idx_flows_latest
    ON flows (company_id, id, reversion DESC);

CREATE INDEX IF NOT EXISTS idx_flows_creator_id
    ON flows (creator_id);

CREATE INDEX IF NOT EXISTS idx_flows_updater_id
    ON flows (updater_id);

CREATE INDEX IF NOT EXISTS idx_flows_deleter_id
    ON flows (deleter_id)
    WHERE deleter_id IS NOT NULL;
