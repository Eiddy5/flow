BEGIN;

ALTER TABLE flow_drafts
    ADD COLUMN IF NOT EXISTS draft boolean,
    ADD COLUMN IF NOT EXISTS deleted boolean;

UPDATE flow_drafts
SET draft = true
WHERE draft IS NULL;

UPDATE flow_drafts
SET deleted = deleter IS NOT NULL OR deleted_at IS NOT NULL
WHERE deleted IS NULL;

UPDATE flow_drafts
SET deleter = COALESCE(deleter, updater),
    deleted_at = COALESCE(deleted_at, updated_at)
WHERE deleted IS TRUE
  AND (deleter IS NULL OR deleted_at IS NULL);

ALTER TABLE flow_drafts
    ALTER COLUMN draft SET DEFAULT true,
    ALTER COLUMN deleted SET DEFAULT false,
    ALTER COLUMN draft SET NOT NULL,
    ALTER COLUMN deleted SET NOT NULL,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_deleted,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_lifecycle;

ALTER TABLE flow_drafts
    ADD CONSTRAINT ck_flow_drafts_lifecycle
        CHECK (
            draft IS TRUE
            AND (
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

DROP INDEX IF EXISTS idx_flow_drafts_active;

CREATE INDEX IF NOT EXISTS idx_flow_drafts_current
    ON flow_drafts (company_id, id)
    WHERE draft IS TRUE AND deleted IS FALSE;

ALTER TABLE flows
    ADD COLUMN IF NOT EXISTS draft boolean,
    ADD COLUMN IF NOT EXISTS deleted boolean;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'flows'
          AND column_name = 'status'
    ) THEN
        EXECUTE $statement$
            UPDATE flows
            SET draft = false,
                deleted = (
                    status IN ('CLOSE', 'CLOSED')
                    OR deleter IS NOT NULL
                    OR deleted_at IS NOT NULL
                )
            WHERE draft IS NULL
               OR deleted IS NULL
        $statement$;
    ELSE
        UPDATE flows
        SET draft = false
        WHERE draft IS NULL;

        UPDATE flows
        SET deleted = deleter IS NOT NULL OR deleted_at IS NOT NULL
        WHERE deleted IS NULL;
    END IF;
END
$$;

UPDATE flows
SET deleter = COALESCE(deleter, updater),
    deleted_at = COALESCE(deleted_at, updated_at)
WHERE deleted IS TRUE
  AND (deleter IS NULL OR deleted_at IS NULL);

DROP INDEX IF EXISTS uq_flows_deployed;

ALTER TABLE flows
    DROP CONSTRAINT IF EXISTS ck_flows_status,
    DROP CONSTRAINT IF EXISTS ck_flows_deleted,
    DROP CONSTRAINT IF EXISTS ck_flows_lifecycle,
    DROP COLUMN IF EXISTS status,
    ALTER COLUMN draft SET DEFAULT false,
    ALTER COLUMN deleted SET DEFAULT false,
    ALTER COLUMN draft SET NOT NULL,
    ALTER COLUMN deleted SET NOT NULL;

ALTER TABLE flows
    ADD CONSTRAINT ck_flows_lifecycle
        CHECK (
            draft IS FALSE
            AND (
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

COMMIT;
