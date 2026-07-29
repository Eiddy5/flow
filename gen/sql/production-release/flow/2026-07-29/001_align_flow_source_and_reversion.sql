ALTER TABLE flow_drafts
    ADD COLUMN IF NOT EXISTS raw text,
    ADD COLUMN IF NOT EXISTS lock_version bigint NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'flow_drafts'
          AND column_name = 'content'
    ) THEN
        UPDATE flow_drafts
        SET raw = content
        WHERE raw IS NULL
          AND content IS NOT NULL;
    END IF;
END
$$;

UPDATE flow_drafts
SET raw = ''
WHERE raw IS NULL;

ALTER TABLE flow_drafts
    ALTER COLUMN raw SET NOT NULL,
    DROP CONSTRAINT IF EXISTS uq_flow_drafts_company_key,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_flow_id,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_key,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_source_version,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_revision,
    DROP CONSTRAINT IF EXISTS ck_flow_drafts_content,
    DROP COLUMN IF EXISTS flow_id,
    DROP COLUMN IF EXISTS key,
    DROP COLUMN IF EXISTS source_version,
    DROP COLUMN IF EXISTS revision,
    DROP COLUMN IF EXISTS content;

DROP INDEX IF EXISTS idx_flow_drafts_flow;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flow_drafts_lock_version'
          AND conrelid = 'flow_drafts'::regclass
    ) THEN
        ALTER TABLE flow_drafts
            ADD CONSTRAINT ck_flow_drafts_lock_version
            CHECK (lock_version >= 0);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_flow_drafts_active
    ON flow_drafts (company_id, id)
    WHERE deleted_at IS NULL;

ALTER TABLE flows
    ADD COLUMN IF NOT EXISTS inputs jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS outputs jsonb NOT NULL DEFAULT '[]'::jsonb;

UPDATE flows
SET status = 'CLOSED'
WHERE status = 'CLOSE';

DROP INDEX IF EXISTS uq_flows_deployed;

ALTER TABLE flows
    DROP CONSTRAINT IF EXISTS pk_flows,
    DROP CONSTRAINT IF EXISTS uq_flows_company_key_version,
    DROP CONSTRAINT IF EXISTS ck_flows_status,
    DROP CONSTRAINT IF EXISTS ck_flows_version,
    DROP CONSTRAINT IF EXISTS ck_flows_revision;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'flows'
          AND column_name = 'version'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'flows'
          AND column_name = 'reversion'
    ) THEN
        ALTER TABLE flows RENAME COLUMN version TO reversion;
    END IF;
END
$$;

ALTER TABLE flows
    DROP COLUMN IF EXISTS revision;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'pk_flows'
          AND conrelid = 'flows'::regclass
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT pk_flows
            PRIMARY KEY (company_id, id, reversion);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_flows_company_key_reversion'
          AND conrelid = 'flows'::regclass
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT uq_flows_company_key_reversion
            UNIQUE (company_id, key, reversion);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flows_status'
          AND conrelid = 'flows'::regclass
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_status
            CHECK (status IN ('DEPLOYED', 'CLOSED'));
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flows_reversion'
          AND conrelid = 'flows'::regclass
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_reversion
            CHECK (reversion > 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flows_inputs'
          AND conrelid = 'flows'::regclass
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_inputs
            CHECK (jsonb_typeof(inputs) = 'array');
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flows_outputs'
          AND conrelid = 'flows'::regclass
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_outputs
            CHECK (jsonb_typeof(outputs) = 'array');
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_flows_latest
    ON flows (company_id, id, reversion DESC);

ALTER TABLE flow_tasks
    ADD COLUMN IF NOT EXISTS depend_on jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE flow_tasks
    DROP CONSTRAINT IF EXISTS pk_flow_tasks,
    DROP CONSTRAINT IF EXISTS uq_flow_tasks_key,
    DROP CONSTRAINT IF EXISTS uq_flow_tasks_version_id,
    DROP CONSTRAINT IF EXISTS ck_flow_tasks_version,
    DROP COLUMN IF EXISTS description;

DROP INDEX IF EXISTS uq_flow_tasks_order;
DROP INDEX IF EXISTS idx_flow_tasks_tree;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'flow_tasks'
          AND column_name = 'flow_version'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'flow_tasks'
          AND column_name = 'flow_reversion'
    ) THEN
        ALTER TABLE flow_tasks
            RENAME COLUMN flow_version TO flow_reversion;
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'pk_flow_tasks'
          AND conrelid = 'flow_tasks'::regclass
    ) THEN
        ALTER TABLE flow_tasks
            ADD CONSTRAINT pk_flow_tasks
            PRIMARY KEY (company_id, flow_id, flow_reversion, id);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_flow_tasks_key'
          AND conrelid = 'flow_tasks'::regclass
    ) THEN
        ALTER TABLE flow_tasks
            ADD CONSTRAINT uq_flow_tasks_key
            UNIQUE (company_id, flow_id, flow_reversion, key);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flow_tasks_reversion'
          AND conrelid = 'flow_tasks'::regclass
    ) THEN
        ALTER TABLE flow_tasks
            ADD CONSTRAINT ck_flow_tasks_reversion
            CHECK (flow_reversion > 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flow_tasks_depend_on'
          AND conrelid = 'flow_tasks'::regclass
    ) THEN
        ALTER TABLE flow_tasks
            ADD CONSTRAINT ck_flow_tasks_depend_on
            CHECK (jsonb_typeof(depend_on) = 'array');
    END IF;
END
$$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_flow_tasks_order
    ON flow_tasks (
        company_id,
        flow_id,
        flow_reversion,
        COALESCE(parent_id, ''),
        "order"
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_tree
    ON flow_tasks (
        company_id,
        flow_id,
        flow_reversion,
        parent_id,
        "order"
    );

ALTER TABLE executions
    DROP CONSTRAINT IF EXISTS uq_executions_flow,
    DROP CONSTRAINT IF EXISTS ck_executions_version;

DROP INDEX IF EXISTS idx_executions_flow;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'executions'
          AND column_name = 'flow_version'
    ) AND NOT EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_schema = 'public'
          AND table_name = 'executions'
          AND column_name = 'flow_reversion'
    ) THEN
        ALTER TABLE executions
            RENAME COLUMN flow_version TO flow_reversion;
    END IF;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_executions_flow'
          AND conrelid = 'executions'::regclass
    ) THEN
        ALTER TABLE executions
            ADD CONSTRAINT uq_executions_flow
            UNIQUE (company_id, id, flow_id, flow_reversion);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_executions_reversion'
          AND conrelid = 'executions'::regclass
    ) THEN
        ALTER TABLE executions
            ADD CONSTRAINT ck_executions_reversion
            CHECK (flow_reversion > 0);
    END IF;
END
$$;

CREATE INDEX IF NOT EXISTS idx_executions_flow
    ON executions (company_id, flow_id, flow_reversion);
