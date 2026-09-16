-- Apache-2.0, db-scheduler v16.11.0. Third-party infrastructure schema.
create table if not exists scheduled_tasks (
  task_name text not null,
  task_instance text not null,
  task_data bytea,
  execution_time timestamp with time zone not null,
  picked BOOLEAN not null,
  picked_by text,
  last_success timestamp with time zone,
  last_failure timestamp with time zone,
  consecutive_failures INT,
  last_heartbeat timestamp with time zone,
  version BIGINT not null,
  priority SMALLINT,
  PRIMARY KEY (task_name, task_instance)
);

CREATE INDEX IF NOT EXISTS execution_time_idx ON scheduled_tasks (execution_time);
CREATE INDEX IF NOT EXISTS last_heartbeat_idx ON scheduled_tasks (last_heartbeat);
CREATE INDEX IF NOT EXISTS priority_execution_time_idx on scheduled_tasks (priority desc, execution_time asc);

-- an optimization for users of priority might be to add priority to the execution_time_idx
-- this _might_ save reads as the priority-value is already in the index
-- CREATE INDEX IF NOT EXISTS execution_time_idx ON scheduled_tasks (execution_time asc, priority desc);
