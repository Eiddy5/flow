ALTER TABLE flow_tasks
    ALTER COLUMN type TYPE text;

ALTER TABLE flow_tasks
    DROP CONSTRAINT IF EXISTS ck_flow_tasks_type;

ALTER TABLE flow_tasks
    ADD CONSTRAINT ck_flow_tasks_type
    CHECK (
        type <> ''
        AND type = btrim(type)
    );
