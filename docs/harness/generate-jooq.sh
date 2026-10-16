#!/usr/bin/env bash
set -euo pipefail

# 只使用本脚本创建的空数据库；不读取业务连接，不随普通编译自动运行。
flow_gen_root="$(cd "$(dirname "$0")/../.." && pwd)"
flow_gen_migrations="$flow_gen_root/jdbc-postgres/src/main/resources/migrations"
flow_gen_scripts=(
    baseline-postgres.sql
    baseline-queue-postgres.sql
    2.0.01-schema-postgres.sql
    2.0.02-queue-postgres.sql
    2.0.06-widen-logs-postgres.sql
    2.0.09-execution-outputs-postgres.sql
    2.0.12-fix-state-duration-postgres.sql
    2.1.02-trigger-source-disabled-postgres.sql
)
for flow_gen_script in "${flow_gen_scripts[@]}"; do
    if [[ ! -f "$flow_gen_migrations/$flow_gen_script" ]]; then
        printf 'Missing migration: %s\n' "$flow_gen_script" >&2
        exit 1
    fi
done

mkdir -p "$flow_gen_root/build/gen-validation"
flow_gen_run_dir="$(mktemp -d "$flow_gen_root/build/gen-validation/run.XXXXXXXX")"
flow_gen_container_id=""

flow_gen_cleanup() {
    flow_gen_status=$?
    trap - EXIT INT TERM
    if [[ -n "$flow_gen_container_id" ]]; then
        if [[ -d "$flow_gen_run_dir" ]]; then
            docker logs "$flow_gen_container_id" >"$flow_gen_run_dir/postgres.log" 2>&1 || true
        fi
        # Cleanup must also work if a forwarded Gradle clean removed build/gen-validation.
        if ! docker rm --force --volumes "$flow_gen_container_id" >/dev/null; then
            printf 'Container cleanup failed: %s\n' "$flow_gen_container_id" >&2
            if [[ "$flow_gen_status" -eq 0 ]]; then
                flow_gen_status=1
            fi
        fi
    fi
    printf 'Generation logs: %s\n' "$flow_gen_run_dir"
    exit "$flow_gen_status"
}
trap flow_gen_cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

printf 'Generation logs: %s\n' "$flow_gen_run_dir"
flow_gen_container_id="$(docker run --detach \
    --name "flow-gen-${flow_gen_run_dir##*/}" \
    --env POSTGRES_DB=flow --env POSTGRES_USER=flow --env POSTGRES_PASSWORD=flow \
    --publish 127.0.0.1::5432 postgres:17 2>"$flow_gen_run_dir/docker-start.log")"

flow_gen_ready=false
for ((flow_gen_attempt=0; flow_gen_attempt<60; flow_gen_attempt++)); do
    if docker exec "$flow_gen_container_id" pg_isready --host 127.0.0.1 --username flow --dbname flow >/dev/null 2>&1; then
        flow_gen_ready=true
        break
    fi
    sleep 1
done
if [[ "$flow_gen_ready" != true ]]; then
    printf 'Temporary PostgreSQL did not become ready.\n' >&2
    exit 1
fi

flow_gen_binding="$(docker port "$flow_gen_container_id" 5432/tcp)"
if [[ ! "$flow_gen_binding" =~ ^127\.0\.0\.1:[0-9]+$ ]]; then
    printf 'Unexpected PostgreSQL port binding: %s\n' "$flow_gen_binding" >&2
    exit 1
fi
flow_gen_port="${flow_gen_binding##*:}"

# 与 JdbcMigrationHistoryStore.bootstrapIfNeeded 的结构一致；不伪造已执行记录。
docker exec --interactive "$flow_gen_container_id" \
    psql -X -v ON_ERROR_STOP=1 --username flow --dbname flow --no-password \
    >"$flow_gen_run_dir/schema.log" 2>&1 <<'SQL'
CREATE TABLE IF NOT EXISTS flow_migration_history (
    script_id    VARCHAR(255) NOT NULL,
    description  VARCHAR(500) NOT NULL,
    checksum     VARCHAR(64),
    installed_on TIMESTAMP    NOT NULL,
    execution_ms BIGINT       NOT NULL,
    success      BOOLEAN      NOT NULL,
    CONSTRAINT pk_flow_migration_history PRIMARY KEY (script_id)
);
SQL

# Fresh-install SQL 按迁移顺序执行。空库不需要 Java 数据搬迁，也不执行 drop-legacy。
# 部分 SQL 包含 CONCURRENTLY，不能用 psql -1 包进单一事务。
for flow_gen_script in "${flow_gen_scripts[@]}"; do
    flow_gen_input="$flow_gen_migrations/$flow_gen_script"
    if [[ "$flow_gen_script" == "2.0.06-widen-logs-postgres.sql" ]]; then
        flow_gen_input="$flow_gen_run_dir/$flow_gen_script"
        sed 's/${table}/logs/g' "$flow_gen_migrations/$flow_gen_script" >"$flow_gen_input"
    fi
    printf 'Applying %s\n' "$flow_gen_script" | tee -a "$flow_gen_run_dir/schema.log"
    docker exec --interactive "$flow_gen_container_id" \
        psql -X -v ON_ERROR_STOP=1 --username flow --dbname flow --no-password \
        <"$flow_gen_input" >>"$flow_gen_run_dir/schema.log" 2>&1
done

env FLOW_GEN_URL="jdbc:postgresql://127.0.0.1:$flow_gen_port/flow" \
    FLOW_GEN_USER=flow FLOW_GEN_PASSWORD=flow FLOW_GEN_SCHEMA=public \
    "$flow_gen_root/gradlew" -p "$flow_gen_root" :gen:generateJooq "$@" \
    2>&1 | tee "$flow_gen_run_dir/generate-jooq.log"
