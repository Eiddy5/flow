CREATE TABLE IF NOT EXISTS flows (
    id             varchar(64) NOT NULL,
    key            varchar(128) NOT NULL,
    company_id     varchar(64) NOT NULL,
    version        bigint,
    draft          boolean NOT NULL DEFAULT true,
    source         text NOT NULL,
    description    text NOT NULL DEFAULT '',

    creator        jsonb NOT NULL,
    updater        jsonb NOT NULL,
    deleter        jsonb,

    created_at     bigint NOT NULL,
    updated_at     bigint NOT NULL,
    deleted_at     bigint,

    inputs         jsonb NOT NULL DEFAULT '[]'::jsonb,
    outputs        jsonb NOT NULL DEFAULT '[]'::jsonb,
    variables      jsonb NOT NULL DEFAULT '{}'::jsonb,
    status         varchar(32) NOT NULL DEFAULT 'Open',

    CONSTRAINT pk_flows
    PRIMARY KEY (company_id, id)
    );

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_draft_key
    ON flows (company_id, key)
    WHERE draft;

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_deployed_key_reversion
    ON flows (company_id, key, version)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_latest_deployed
    ON flows (company_id, key, version DESC)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_editable_drafts
    ON flows (company_id, key)
    WHERE draft AND status <> 'Delete';
