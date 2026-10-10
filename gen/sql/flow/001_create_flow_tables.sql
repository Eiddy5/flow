-- Flow is still in development. This file is the complete schema entry point.
-- Change the table-owned scripts under tables/ and recreate existing
-- development databases; this baseline does not upgrade older data.

\set ON_ERROR_STOP on

BEGIN;

\ir tables/flows.sql
\ir tables/flow_tasks.sql
\ir tables/executions.sql
\ir tables/task_runs.sql
\ir tables/task_retry_messages.sql

\ir tables/scheduler_events.sql
\ir tables/trigger_runs.sql
\ir tables/trigger_schedules.sql

COMMIT;
