ALTER TABLE flows
    ADD COLUMN IF NOT EXISTS description text NOT NULL DEFAULT '';

ALTER TABLE flow_drafts
    ADD COLUMN IF NOT EXISTS key varchar(128);

UPDATE flow_drafts
SET key = flow_id
WHERE key IS NULL;

ALTER TABLE flow_drafts
    ALTER COLUMN key SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_flow_drafts_company_key'
          AND conrelid = 'flow_drafts'::regclass
    ) THEN
        ALTER TABLE flow_drafts
            ADD CONSTRAINT uq_flow_drafts_company_key
            UNIQUE (company_id, key);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flow_drafts_key'
          AND conrelid = 'flow_drafts'::regclass
    ) THEN
        ALTER TABLE flow_drafts
            ADD CONSTRAINT ck_flow_drafts_key
            CHECK (length(btrim(key)) BETWEEN 1 AND 128);
    END IF;
END
$$;

ALTER TABLE flow_tasks
    ADD COLUMN IF NOT EXISTS inputs jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS outputs jsonb NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS properties jsonb NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE flow_tasks
    DROP CONSTRAINT IF EXISTS uq_flow_tasks_id,
    DROP CONSTRAINT IF EXISTS pk_flow_tasks;

ALTER TABLE flow_tasks
    ADD CONSTRAINT pk_flow_tasks
    PRIMARY KEY (company_id, flow_id, flow_version, id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flow_tasks_inputs'
          AND conrelid = 'flow_tasks'::regclass
    ) THEN
        ALTER TABLE flow_tasks
            ADD CONSTRAINT ck_flow_tasks_inputs
            CHECK (jsonb_typeof(inputs) = 'array');
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flow_tasks_outputs'
          AND conrelid = 'flow_tasks'::regclass
    ) THEN
        ALTER TABLE flow_tasks
            ADD CONSTRAINT ck_flow_tasks_outputs
            CHECK (jsonb_typeof(outputs) = 'array');
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_flow_tasks_properties'
          AND conrelid = 'flow_tasks'::regclass
    ) THEN
        ALTER TABLE flow_tasks
            ADD CONSTRAINT ck_flow_tasks_properties
            CHECK (jsonb_typeof(properties) = 'object');
    END IF;
END
$$;

ALTER TABLE executions
    ADD COLUMN IF NOT EXISTS lock_version bigint NOT NULL DEFAULT 0;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_executions_lock_version'
          AND conrelid = 'executions'::regclass
    ) THEN
        ALTER TABLE executions
            ADD CONSTRAINT ck_executions_lock_version
            CHECK (lock_version >= 0);
    END IF;
END
$$;

DO $$
DECLARE
    target_table text;
    target_column text;
BEGIN
    FOREACH target_table IN ARRAY ARRAY['flows', 'flow_drafts']
    LOOP
        FOREACH target_column IN ARRAY ARRAY[
            'creator_id',
            'updater_id',
            'deleter_id'
        ]
        LOOP
            IF EXISTS (
                SELECT 1
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = target_table
                  AND column_name = target_column
                  AND data_type = 'bigint'
            ) THEN
                EXECUTE format(
                    'DROP INDEX IF EXISTS %I',
                    'idx_' || target_table || '_' || target_column
                );
                EXECUTE format(
                    'ALTER TABLE %I DROP COLUMN %I',
                    target_table,
                    target_column
                );
                EXECUTE format(
                    'ALTER TABLE %I ADD COLUMN %I varchar(64) '
                    || 'GENERATED ALWAYS AS (%I ->> ''id'') STORED',
                    target_table,
                    target_column,
                    replace(target_column, '_id', '')
                );
            END IF;
        END LOOP;
    END LOOP;
END
$$;

CREATE INDEX IF NOT EXISTS idx_flows_creator_id
    ON flows (creator_id);
CREATE INDEX IF NOT EXISTS idx_flows_updater_id
    ON flows (updater_id);
CREATE INDEX IF NOT EXISTS idx_flows_deleter_id
    ON flows (deleter_id)
    WHERE deleter_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_flow_drafts_creator_id
    ON flow_drafts (creator_id);
CREATE INDEX IF NOT EXISTS idx_flow_drafts_updater_id
    ON flow_drafts (updater_id);
CREATE INDEX IF NOT EXISTS idx_flow_drafts_deleter_id
    ON flow_drafts (deleter_id)
    WHERE deleter_id IS NOT NULL;
