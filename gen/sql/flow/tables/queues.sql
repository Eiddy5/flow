CREATE TABLE IF NOT EXISTS queues (
    id            varchar(64) NOT NULL,
    queue_type    varchar(32) NOT NULL,
    queue_name    varchar(250) NOT NULL,
    event_key     text,
    payload       jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_queues
        PRIMARY KEY (id),
    CONSTRAINT ck_queues_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_queues_type
        CHECK (length(btrim(queue_type)) > 0),
    CONSTRAINT ck_queues_payload
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_queues_pending
    ON queues (queue_type, queue_name, created_at, id);
