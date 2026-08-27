CREATE TABLE IF NOT EXISTS queues (
    id            varchar(64) NOT NULL,
    queue_type    varchar(32) NOT NULL,
    queue_name    varchar(250) NOT NULL,
    event_key     text,
    payload       jsonb NOT NULL,
    created_at    bigint NOT NULL DEFAULT (extract(epoch FROM now()) * 1000)::bigint,

    CONSTRAINT pk_queues
        PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_queues_pending
    ON queues (queue_type, queue_name, created_at, id);
