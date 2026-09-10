-- Flow is still in development. This file is the complete schema entry point.
-- Change the table-owned scripts under tables/ and recreate existing
-- development databases; this baseline does not upgrade older data.

\set ON_ERROR_STOP on

BEGIN;

\ir tables/flows.sql
\ir tables/flow_tasks.sql
\ir tables/executions.sql
\ir tables/task_runs.sql

COMMIT;
