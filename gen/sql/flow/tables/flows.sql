-- Draft rows are physically deleted; deployed rows retain soft-delete audit facts.
CREATE TABLE IF NOT EXISTS flows (
    company_id     varchar(64) NOT NULL,
    id             varchar(64) NOT NULL,
    key            varchar(128) NOT NULL,
    version        bigint,

    draft          boolean NOT NULL,
    source         text NOT NULL,
    description    text NOT NULL,

    variables      jsonb NOT NULL,
    inputs         jsonb NOT NULL,
    outputs        jsonb NOT NULL,

    status         varchar(64) NOT NULL,

    creator        jsonb NOT NULL,
    created_at     bigint NOT NULL,
    updater        jsonb NOT NULL,
    updated_at     bigint NOT NULL,
    deleter        jsonb,
    deleted_at     bigint,

    CONSTRAINT pk_flows
        PRIMARY KEY (company_id, id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_draft_key
    ON flows (company_id, key)
    WHERE draft;

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_deployed_key_version
    ON flows (company_id, key, version)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_latest_deployed
    ON flows (company_id, key, version DESC)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_active_drafts
    ON flows (company_id, updated_at DESC, id)
    WHERE draft;
