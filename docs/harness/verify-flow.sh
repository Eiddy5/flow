#!/usr/bin/env bash
set -euo pipefail

# 只创建、清理本脚本持有的临时 PostgreSQL 容器，不读取任何业务连接配置。
flow_check_root="$(cd "$(dirname "$0")/../.." && pwd)"
flow_check_container_id=""
flow_check_plugins="$(mktemp -d "${TMPDIR:-/tmp}/flow-check-plugins.XXXXXX")"
flow_check_tasks=(:core:test --tests 'org.flow.core.runners.FlowEngineTest'
    --tests 'org.flow.core.jdbc.postgres.PostgresSettingRepositoryTest'
    --tests 'org.flow.core.jdbc.postgres.PostgresSortMetadataTest'
    --tests 'org.flow.core.jdbc.postgres.PostgresSchemaTest')
if [[ "${1:-}" == "--full-regression" ]]; then
    # 完整回归也只连接本脚本创建的独立数据库。
    flow_check_tasks=(test --continue)
    shift
fi
flow_check_cleanup() {
    rm -rf "$flow_check_plugins"
    if [[ -n "$flow_check_container_id" ]]; then
        docker rm --force --volumes "$flow_check_container_id" >/dev/null
    fi
}
trap flow_check_cleanup EXIT

flow_check_container_id="$(docker run --detach \
    --name "flow-check-$$" \
    --env POSTGRES_DB=flow --env POSTGRES_USER=flow --env POSTGRES_PASSWORD=flow \
    --publish 127.0.0.1::5432 postgres:17)"

flow_check_ready=false
for ((flow_check_attempt=0; flow_check_attempt<60; flow_check_attempt++)); do
    if docker exec "$flow_check_container_id" pg_isready --username flow --dbname flow >/dev/null 2>&1; then
        flow_check_ready=true
        break
    fi
    sleep 1
done
if [[ "$flow_check_ready" != true ]]; then
    docker logs "$flow_check_container_id"
    exit 1
fi
docker exec "$flow_check_container_id" createdb --username flow flow_unit
docker exec "$flow_check_container_id" createdb --username flow flow_logs
flow_check_binding="$(docker port "$flow_check_container_id" 5432/tcp)"
flow_check_port="${flow_check_binding##*:}"

FLOW_UPSTREAM_TEST_URL="jdbc:postgresql://127.0.0.1:$flow_check_port/flow_unit" \
FLOW_LOGS_TEST_URL="jdbc:postgresql://127.0.0.1:$flow_check_port/flow_logs" \
FLOW_UPSTREAM_TEST_USER=flow \
FLOW_UPSTREAM_TEST_PASSWORD=flow \
    "$flow_check_root/gradlew" -p "$flow_check_root" \
    "-PschemaPlugins=$flow_check_plugins" \
    "${flow_check_tasks[@]}" :jar :verifyRuntimeIsolation \
    :tooling:test :tooling:verifyRuntimeIsolation :tooling:exportConfigurationSchema "$@"
