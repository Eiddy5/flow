CREATE TABLE IF NOT EXISTS flows (
    id             varchar(64) NOT NULL,
    key            varchar(128) NOT NULL,
    company_id     varchar(64) NOT NULL,
    reversion      bigint,
    draft          boolean NOT NULL DEFAULT true,
    source         text NOT NULL,
    lock_version   bigint NOT NULL DEFAULT 0,
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
    variables      jsonb NOT NULL DEFAULT '{}'::jsonb,
    status         varchar(32) NOT NULL DEFAULT 'Open',

    CONSTRAINT pk_flows
        PRIMARY KEY (company_id, id),
    CONSTRAINT ck_flows_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_flows_key
        CHECK (length(btrim(key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flows_definition_state
        CHECK (
            (draft AND reversion IS NULL)
            OR (NOT draft AND reversion > 0)
        ),
    CONSTRAINT ck_flows_lock_version
        CHECK (lock_version >= 0),
    CONSTRAINT ck_flows_inputs
        CHECK (jsonb_typeof(inputs) = 'array'),
    CONSTRAINT ck_flows_outputs
        CHECK (jsonb_typeof(outputs) = 'array'),
    CONSTRAINT ck_flows_variables
        CHECK (jsonb_typeof(variables) = 'object'),
    CONSTRAINT ck_flows_status
        CHECK (
            length(btrim(status)) > 0
            AND status = btrim(status)
            AND (lower(status) <> 'delete' OR status = 'Delete')
        ),
    CONSTRAINT ck_flows_audit_time
        CHECK (updated_at >= created_at),
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
    CONSTRAINT ck_flows_deletion_audit
        CHECK (
            (
                status <> 'Delete'
                AND deleter IS NULL
                AND deleted_at IS NULL
            )
            OR (
                status = 'Delete'
                AND deleter IS NOT NULL
                AND deleted_at IS NOT NULL
                AND updater = deleter
                AND updated_at = deleted_at
            )
        )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_draft_key
    ON flows (company_id, key)
    WHERE draft;

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_deployed_key_reversion
    ON flows (company_id, key, reversion)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_latest_deployed
    ON flows (company_id, key, reversion DESC)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_editable_drafts
    ON flows (company_id, key)
    WHERE draft AND status <> 'Delete';

CREATE INDEX IF NOT EXISTS idx_flows_creator_id
    ON flows (creator_id);

CREATE INDEX IF NOT EXISTS idx_flows_updater_id
    ON flows (updater_id);

CREATE INDEX IF NOT EXISTS idx_flows_deleter_id
    ON flows (deleter_id)
    WHERE deleter_id IS NOT NULL;
