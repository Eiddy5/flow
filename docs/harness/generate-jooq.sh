#!/usr/bin/env bash
set -euo pipefail

# 只使用本脚本创建的空数据库；不读取业务连接，不随普通编译自动运行。
flow_gen_root="$(cd "$(dirname "$0")/../.." && pwd)"
flow_gen_schema="$flow_gen_root/core/src/main/resources/db/postgres"

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

# Apply the same baseline as PostgresSchema in a single transaction.
{
    cat "$flow_gen_schema/types.sql" "$flow_gen_schema/tables.sql"
    sed 's/${table}/logs/g' "$flow_gen_schema/logs.sql"
} | docker exec --interactive "$flow_gen_container_id" \
    psql -X --single-transaction -v ON_ERROR_STOP=1 --username flow --dbname flow --no-password --file=- \
    >"$flow_gen_run_dir/schema.log" 2>&1

env FLOW_GEN_URL="jdbc:postgresql://127.0.0.1:$flow_gen_port/flow" \
    FLOW_GEN_USER=flow FLOW_GEN_PASSWORD=flow FLOW_GEN_SCHEMA=public \
    "$flow_gen_root/gradlew" -p "$flow_gen_root" :gen:generateJooq "$@" \
    2>&1 | tee "$flow_gen_run_dir/generate-jooq.log"
