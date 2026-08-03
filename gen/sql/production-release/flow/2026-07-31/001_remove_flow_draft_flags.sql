BEGIN;

DROP INDEX IF EXISTS idx_flow_drafts_current;

ALTER TABLE flow_drafts
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_lifecycle,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_deleted,
    DROP COLUMN IF EXISTS draft;

ALTER TABLE flow_drafts
    ADD CONSTRAINT ck_flow_drafts_deleted
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
        );

CREATE INDEX IF NOT EXISTS idx_flow_drafts_current
    ON flow_drafts (company_id, id)
    WHERE deleted IS FALSE;

ALTER TABLE flows
    DROP CONSTRAINT IF EXISTS ck_flows_lifecycle,
    DROP CONSTRAINT IF EXISTS ck_flows_deleted,
    DROP COLUMN IF EXISTS draft;

ALTER TABLE flows
    ADD CONSTRAINT ck_flows_deleted
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
        );

COMMIT;
