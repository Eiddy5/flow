-- Log table. Apply types.sql first and substitute ${table} with the configured table identifier.

CREATE TABLE IF NOT EXISTS "${table}" (
    key VARCHAR(30) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL,
    namespace VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'namespace') STORED,
    flow_id VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'flowId') STORED,
    task_id VARCHAR(256) GENERATED ALWAYS AS (value ->> 'taskId') STORED,
    execution_id VARCHAR(150) GENERATED ALWAYS AS (value ->> 'executionId') STORED,
    taskrun_id VARCHAR(150) GENERATED ALWAYS AS (value ->> 'taskRunId') STORED,
    attempt_number INT GENERATED ALWAYS AS (CAST(value ->> 'attemptNumber' AS INTEGER)) STORED,
    trigger_id VARCHAR(256) GENERATED ALWAYS AS (value ->> 'triggerId') STORED,
    level log_level NOT NULL GENERATED ALWAYS AS (LOGLEVEL_FROMTEXT(value ->> 'level')) STORED,
    timestamp TIMESTAMPTZ NOT NULL GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'timestamp')) STORED,
    fulltext TSVECTOR GENERATED ALWAYS AS (
        FULLTEXT_INDEX(CAST(value ->> 'namespace' AS varchar)) ||
        FULLTEXT_INDEX(CAST(value ->> 'flowId' AS varchar)) ||
        FULLTEXT_INDEX(COALESCE(CAST(value ->> 'taskId' AS varchar), '')) ||
        FULLTEXT_INDEX(COALESCE(CAST(value ->> 'executionId' AS varchar), '')) ||
        FULLTEXT_INDEX(COALESCE(CAST(value ->> 'taskRunId' AS varchar), '')) ||
        FULLTEXT_INDEX(COALESCE(CAST(value ->> 'triggerId' AS varchar), '')) ||
        FULLTEXT_INDEX(COALESCE(CAST(value ->> 'message' AS varchar), '')) ||
        FULLTEXT_INDEX(COALESCE(CAST(value ->> 'thread' AS varchar), ''))
    ) STORED,
    tenant_id VARCHAR(250) GENERATED ALWAYS AS (value ->> 'tenantId') STORED,
    execution_kind VARCHAR(32) GENERATED ALWAYS AS (value ->> 'executionKind') STORED
);

CREATE INDEX IF NOT EXISTS "${table}_execution_id__task_id" ON "${table}" (execution_id, task_id);
CREATE INDEX IF NOT EXISTS "${table}_execution_id__taskrun_id" ON "${table}" (execution_id, taskrun_id);
CREATE INDEX IF NOT EXISTS "${table}_fulltext" ON "${table}" USING GIN (fulltext);
CREATE INDEX IF NOT EXISTS "${table}_tenant_timestamp" ON "${table}" ("tenant_id", "timestamp", "level");
CREATE INDEX IF NOT EXISTS "${table}_tenant_namespace_timestamp" ON "${table}" ("tenant_id", "namespace", "timestamp", "level");
CREATE INDEX IF NOT EXISTS "${table}_tenant_namespace_flow_id_timestamp" ON "${table}" ("tenant_id", "namespace", "flow_id", "timestamp", "level");
