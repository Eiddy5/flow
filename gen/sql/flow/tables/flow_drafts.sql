CREATE TABLE IF NOT EXISTS flow_drafts (
    id               varchar(64) NOT NULL,
    company_id       varchar(64) NOT NULL,
    flow_key         varchar(128) NOT NULL,

    creator          jsonb NOT NULL,
    creator_id       varchar(64)
        GENERATED ALWAYS AS (creator ->> 'id') STORED,
    updater          jsonb NOT NULL,
    updater_id       varchar(64)
        GENERATED ALWAYS AS (updater ->> 'id') STORED,
    deleter          jsonb,
    deleter_id       varchar(64)
        GENERATED ALWAYS AS (deleter ->> 'id') STORED,

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,

    raw              text NOT NULL,
    lock_version     bigint NOT NULL DEFAULT 0,
    deleted          boolean NOT NULL DEFAULT false,

    CONSTRAINT pk_flow_drafts
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_flow_drafts_company_flow_key
        UNIQUE (company_id, flow_key),
    CONSTRAINT ck_flow_drafts_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_flow_drafts_flow_key
        CHECK (length(btrim(flow_key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flow_drafts_lock_version
        CHECK (lock_version >= 0),
    CONSTRAINT ck_flow_drafts_creator
        CHECK (
            jsonb_typeof(creator) = 'object'
            AND creator ? 'id'
        ),
    CONSTRAINT ck_flow_drafts_updater
        CHECK (
            jsonb_typeof(updater) = 'object'
            AND updater ? 'id'
        ),
    CONSTRAINT ck_flow_drafts_deleter
        CHECK (
            deleter IS NULL
            OR (
                jsonb_typeof(deleter) = 'object'
                AND deleter ? 'id'
            )
        ),
    CONSTRAINT ck_flow_drafts_deleted
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

CREATE INDEX IF NOT EXISTS idx_flow_drafts_current
    ON flow_drafts (company_id, id)
    WHERE deleted IS FALSE;

CREATE INDEX IF NOT EXISTS idx_flow_drafts_creator_id
    ON flow_drafts (creator_id);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_updater_id
    ON flow_drafts (updater_id);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_deleter_id
    ON flow_drafts (deleter_id)
    WHERE deleter_id IS NOT NULL;
