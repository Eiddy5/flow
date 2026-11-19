-- Flow runtime tables. Apply types.sql before this file.

/* ----------------------- flows ----------------------- */
CREATE TABLE IF NOT EXISTS flows (
    key VARCHAR(250) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL,
    deleted BOOL NOT NULL GENERATED ALWAYS AS (CAST(value ->> 'deleted' AS BOOL)) STORED,
    id VARCHAR(100) NOT NULL GENERATED ALWAYS AS (value ->> 'id') STORED,
    namespace VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'namespace') STORED,
    revision INT NOT NULL GENERATED ALWAYS AS (CAST(value ->> 'revision' AS INT)) STORED,
    fulltext TSVECTOR GENERATED ALWAYS AS (
        FULLTEXT_INDEX(CAST(value->>'namespace' AS VARCHAR)) ||
        FULLTEXT_INDEX(CAST(value->>'id' AS VARCHAR))
    ) STORED,
    source_code TEXT NOT NULL,
    tenant_id VARCHAR(250) GENERATED ALWAYS AS (value ->> 'tenantId') STORED,
    updated VARCHAR(250) GENERATED ALWAYS AS (value ->> 'updated') STORED,
    draft BOOL NOT NULL GENERATED ALWAYS AS (COALESCE(CAST(value ->> 'draft' AS BOOL), FALSE)) STORED,
    disabled BOOL NOT NULL GENERATED ALWAYS AS (COALESCE(CAST(value ->> 'disabled' AS BOOL), FALSE)) STORED
);

CREATE INDEX IF NOT EXISTS flows_namespace ON flows (deleted, tenant_id, namespace);
CREATE INDEX IF NOT EXISTS flows_namespace__id__revision ON flows (deleted, tenant_id, namespace, id, revision);
CREATE INDEX IF NOT EXISTS flows_fulltext ON flows USING GIN (fulltext);
CREATE INDEX IF NOT EXISTS flows_source_code ON flows USING GIN (FULLTEXT_INDEX(source_code));
CREATE INDEX IF NOT EXISTS flows_labels ON flows USING GIN ((value -> 'labels'));
CREATE INDEX IF NOT EXISTS flows_draft ON flows (deleted, draft, namespace, id, revision);


/* ----------------------- executions ----------------------- */
CREATE TABLE IF NOT EXISTS executions (
    key VARCHAR(250) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL,
    deleted BOOL NOT NULL GENERATED ALWAYS AS (CAST(value ->> 'deleted' AS bool)) STORED,
    id VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'id') STORED,
    namespace VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'namespace') STORED,
    flow_id VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'flowId') STORED,
    state_current state_type NOT NULL GENERATED ALWAYS AS (STATE_FROMTEXT(value #>> '{state, current}')) STORED,
    start_date TIMESTAMP NOT NULL GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value #>> '{state, startDate}')) STORED,
    end_date TIMESTAMP GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value #>> '{state, endDate}')) STORED,
    fulltext TSVECTOR GENERATED ALWAYS AS (
        FULLTEXT_INDEX(CAST(value ->> 'namespace' AS varchar)) ||
        FULLTEXT_INDEX(CAST(value ->> 'flowId' AS varchar)) ||
        FULLTEXT_INDEX(CAST(value ->> 'id' AS varchar))
    ) STORED,
    tenant_id VARCHAR(250) GENERATED ALWAYS AS (value ->> 'tenantId') STORED,
    trigger_execution_id VARCHAR(150) GENERATED ALWAYS AS (value #>> '{trigger, variables, executionId}') STORED,
    kind VARCHAR(32) GENERATED ALWAYS AS (value ->> 'kind') STORED,
    trigger_id VARCHAR(256) GENERATED ALWAYS AS (value -> 'trigger' ->> 'id') STORED,
    parent_id VARCHAR(100) GENERATED ALWAYS AS (value #>> '{parentId}') STORED,
    loop_run_index INT GENERATED ALWAYS AS ((value #>> '{loopRun,index}')::INT) STORED,
    loop_run_task_id VARCHAR(256) GENERATED ALWAYS AS (value #>> '{loopRun,taskId}') STORED,
    state_duration BIGINT GENERATED ALWAYS AS ((ROUND(EXTRACT(EPOCH FROM PARSE_ISO8601_DURATION(value #>> '{state, duration}')) * 1000))::bigint) STORED
);

CREATE INDEX IF NOT EXISTS executions_state_current ON executions (deleted, tenant_id, state_current);
CREATE INDEX IF NOT EXISTS executions_start_date ON executions (deleted, tenant_id, start_date);
CREATE INDEX IF NOT EXISTS executions_end_date ON executions (deleted, tenant_id, end_date);
CREATE INDEX IF NOT EXISTS executions_state_duration ON executions (deleted, tenant_id, state_duration);
CREATE INDEX IF NOT EXISTS executions_fulltext ON executions USING GIN (fulltext);
CREATE INDEX IF NOT EXISTS executions_trigger_execution_id ON executions (deleted, tenant_id, trigger_execution_id);
CREATE INDEX IF NOT EXISTS executions_labels ON executions USING GIN ((value -> 'labels'));
CREATE INDEX IF NOT EXISTS idx_executions_trigger_id ON executions ("trigger_id") WHERE "trigger_id" IS NOT NULL;
CREATE INDEX IF NOT EXISTS executions_parent_id ON executions ("deleted", "tenant_id", "parent_id") WHERE "parent_id" IS NOT NULL;
CREATE INDEX IF NOT EXISTS executions_namespace__flow_id ON executions ("deleted", "tenant_id", "namespace", "flow_id");


/* ----------------------- triggers ----------------------- */
CREATE TABLE IF NOT EXISTS triggers (
    key VARCHAR(250) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL,
    namespace VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'namespace') STORED,
    flow_id VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'flowId') STORED,
    trigger_id VARCHAR(256) NOT NULL GENERATED ALWAYS AS (value ->> 'triggerId') STORED,
    execution_id VARCHAR(150) GENERATED ALWAYS AS (value ->> 'executionId') STORED,
    fulltext TSVECTOR GENERATED ALWAYS AS (
        FULLTEXT_INDEX(CAST(value ->> 'namespace' AS varchar)) ||
        FULLTEXT_INDEX(CAST(value ->> 'flowId' AS varchar)) ||
        FULLTEXT_INDEX(CAST(value ->> 'triggerId' AS varchar)) ||
        FULLTEXT_INDEX(COALESCE(CAST(value ->> 'executionId' AS varchar), ''))
    ) STORED,
    tenant_id VARCHAR(250) GENERATED ALWAYS AS (value ->> 'tenantId') STORED,
    worker_id VARCHAR(250) GENERATED ALWAYS AS (value ->> 'workerId') STORED,
    disabled BOOL NOT NULL GENERATED ALWAYS AS (CAST(value ->> 'disabled' AS BOOL)) STORED,
    vnode INTEGER GENERATED ALWAYS AS (CAST(value ->> 'vnode' AS INTEGER)) STORED,
    locked BOOLEAN GENERATED ALWAYS AS (CAST(value ->> 'locked' AS BOOLEAN)) STORED,
    next_evaluation_epoch BIGINT GENERATED ALWAYS AS (CAST(value ->> 'nextEvaluationEpoch' AS BIGINT)) STORED,
    next_evaluation_date TIMESTAMPTZ GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'nextEvaluationDate')) STORED,
    last_triggered_date TIMESTAMPTZ GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'lastTriggeredDate')) STORED,
    type VARCHAR(250) GENERATED ALWAYS AS (value ->> 'type') STORED,
    source_disabled BOOLEAN NOT NULL GENERATED ALWAYS AS (COALESCE(CAST(value ->> 'sourceDisabled' AS BOOLEAN), FALSE)) STORED
);

CREATE INDEX IF NOT EXISTS triggers_execution_id ON triggers (execution_id);
CREATE INDEX IF NOT EXISTS triggers__tenant ON triggers (tenant_id);
CREATE INDEX IF NOT EXISTS idx_trigger_scheduler            ON triggers (vnode, next_evaluation_epoch, locked);
CREATE INDEX IF NOT EXISTS idx_trigger_next_evaluation_date ON triggers (next_evaluation_date);


/* ----------------------- multipleconditions ----------------------- */
CREATE TABLE IF NOT EXISTS multipleconditions (
    key VARCHAR(250) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL,
    namespace VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'namespace') STORED,
    flow_id VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'flowId') STORED,
    condition_id VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'conditionId') STORED,
    start_date TIMESTAMPTZ NOT NULL GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'start')) STORED,
    end_date TIMESTAMPTZ NOT NULL GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'end')) STORED,
    tenant_id VARCHAR(250) GENERATED ALWAYS AS (value ->> 'tenantId') STORED
);

CREATE INDEX IF NOT EXISTS multipleconditions_namespace__flow_id__condition_id ON multipleconditions (tenant_id, namespace, flow_id, condition_id);
CREATE INDEX IF NOT EXISTS multipleconditions_start_date__end_date ON multipleconditions (tenant_id, start_date, end_date);


/* ----------------------- executordelayed ----------------------- */
CREATE TABLE IF NOT EXISTS executordelayed (
    key VARCHAR(250) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL,
    date TIMESTAMPTZ NOT NULL GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'date')) STORED
);

CREATE INDEX IF NOT EXISTS executordelayed_date ON executordelayed (date);


/* ----------------------- settings ----------------------- */
CREATE TABLE IF NOT EXISTS settings (
    key VARCHAR(250) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL
);


/* ----------------------- service_instance ----------------------- */
CREATE TABLE IF NOT EXISTS service_instance (
    key             VARCHAR(250) NOT NULL PRIMARY KEY,
    value           JSONB        NOT NULL,
    service_id      VARCHAR(36)  NOT NULL GENERATED ALWAYS AS (value ->> 'id') STORED,
    service_type    VARCHAR(36)  NOT NULL GENERATED ALWAYS AS (value ->> 'type') STORED,
    state           VARCHAR(36)  NOT NULL GENERATED ALWAYS AS (value ->> 'state') STORED,
    created_at      TIMESTAMPTZ  NOT NULL GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'createdAt')) STORED,
    updated_at      TIMESTAMPTZ  NOT NULL GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'updatedAt')) STORED,
    fulltext TSVECTOR GENERATED ALWAYS AS (
        FULLTEXT_INDEX(CAST(value ->> 'id' AS varchar)) ||
        FULLTEXT_INDEX(CAST(value ->> 'type' AS varchar)) ||
        FULLTEXT_INDEX(COALESCE(CAST(value -> 'server' ->> 'hostname' AS varchar), '')) ||
        FULLTEXT_INDEX(COALESCE(CAST(value -> 'server' ->> 'version' AS varchar), ''))
    ) STORED
);

CREATE INDEX IF NOT EXISTS ix_service_instance_state ON service_instance (state);
CREATE INDEX IF NOT EXISTS ix_service_instance_type_created_at_updated_at ON service_instance (service_type, created_at, updated_at);
CREATE UNIQUE INDEX IF NOT EXISTS ix_service_id ON service_instance (service_id);
CREATE INDEX IF NOT EXISTS service_instance_fulltext ON service_instance USING GIN (fulltext);


/* ----------------------- worker_job_running ----------------------- */
CREATE TABLE IF NOT EXISTS worker_job_running (
    key VARCHAR(250) NOT NULL PRIMARY KEY,
    value JSONB NOT NULL,
    worker_uid VARCHAR(36) NOT NULL GENERATED ALWAYS AS (value -> 'workerInstance' ->> 'uid') STORED
);

CREATE INDEX IF NOT EXISTS worker_job_running_worker_uid ON worker_job_running (worker_uid);

CREATE TABLE IF NOT EXISTS locks (
    key          VARCHAR(700) NOT NULL PRIMARY KEY,
    value        JSONB        NOT NULL,
    category     VARCHAR(250) NOT NULL GENERATED ALWAYS AS (value ->> 'category') STORED,
    id           VARCHAR(500) NOT NULL GENERATED ALWAYS AS (value ->> 'id') STORED,
    owner        VARCHAR(150) NOT NULL GENERATED ALWAYS AS (value ->> 'owner') STORED,
    tenant_id    VARCHAR(150) GENERATED ALWAYS AS (value ->> 'tenantId') STORED,
    locked_until TIMESTAMPTZ GENERATED ALWAYS AS (PARSE_ISO8601_DATETIME(value ->> 'lockedUntil')) STORED
);

CREATE INDEX IF NOT EXISTS locks__catefory_id ON locks (category, id);

-- Task outputs
CREATE TABLE IF NOT EXISTS task_outputs (
    "key"          VARCHAR(250) PRIMARY KEY,
    "task_run_id"  VARCHAR(150) NOT NULL,
    "tenant_id"    VARCHAR(150) NOT NULL,
    "execution_id" VARCHAR(150) NOT NULL,
    "value"        BYTEA,
    "uri"          VARCHAR(250)
);

CREATE INDEX IF NOT EXISTS task_outputs_execution_id ON task_outputs ("execution_id");

CREATE TABLE IF NOT EXISTS execution_outputs (
    "key"       VARCHAR(250) PRIMARY KEY,
    "tenant_id" VARCHAR(150) NOT NULL,
    "value"     BYTEA,
    "uri"       VARCHAR(250)
);

CREATE TABLE IF NOT EXISTS queues (
    "offset"      SERIAL       PRIMARY KEY,
    type          VARCHAR(250) NOT NULL,
    "routing_key" VARCHAR(250),
    key           VARCHAR(250) NOT NULL,
    value         JSONB        NOT NULL,
    created       TIMESTAMPTZ  NOT NULL
);

CREATE INDEX IF NOT EXISTS queues_type__key__offset ON queues (type, "routing_key", "offset");
CREATE INDEX IF NOT EXISTS queues_type__created ON queues (type, created);
