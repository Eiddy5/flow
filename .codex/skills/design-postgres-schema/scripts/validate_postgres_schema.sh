#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "用法: $0 <完整基线 SQL 文件>" >&2
    exit 2
fi

for schema_sql_file in "$@"; do
    if [[ ! -f "$schema_sql_file" ]]; then
        echo "SQL 文件不存在: $schema_sql_file" >&2
        exit 2
    fi
done

schema_container="postgres-schema-validate-$$"
schema_image="${POSTGRES_IMAGE:-postgres:17-alpine}"
schema_database="schema_validation"
schema_user="schema_validator"
schema_password="schema-validation"

cleanup_schema_container() {
    docker rm -f "$schema_container" >/dev/null 2>&1 || true
}

trap cleanup_schema_container EXIT

docker run \
    --name "$schema_container" \
    -e POSTGRES_DB="$schema_database" \
    -e POSTGRES_USER="$schema_user" \
    -e POSTGRES_PASSWORD="$schema_password" \
    -d "$schema_image" >/dev/null

schema_ready=false
for _ in {1..30}; do
    if docker logs "$schema_container" 2>&1 \
            | grep -q "PostgreSQL init process complete" \
        && docker exec "$schema_container" \
        pg_isready -U "$schema_user" -d "$schema_database" >/dev/null 2>&1; then
        schema_ready=true
        break
    fi
    sleep 1
done

if [[ "$schema_ready" != "true" ]]; then
    echo "PostgreSQL 未能在限定时间内就绪" >&2
    exit 1
fi

execute_schema_files() {
    for schema_sql_file in "$@"; do
        docker exec -i "$schema_container" \
            psql -v ON_ERROR_STOP=1 -U "$schema_user" -d "$schema_database" \
            < "$schema_sql_file" >/dev/null
    done
}

execute_schema_files "$@"
execute_schema_files "$@"

foreign_key_count="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT count(*)
           FROM pg_constraint
          WHERE contype = 'f'
            AND connamespace = 'public'::regnamespace;"
)"

if [[ "$foreign_key_count" != "0" ]]; then
    echo "预期外键数量为 0，实际为: $foreign_key_count" >&2
    exit 1
fi

invalid_time_columns="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT table_name || '.' || column_name || ':' || data_type
           FROM information_schema.columns
          WHERE table_schema = 'public'
            AND column_name ~ '_at$'
            AND data_type <> 'timestamp with time zone'
          ORDER BY table_name, ordinal_position;"
)"

if [[ -n "$invalid_time_columns" ]]; then
    echo "时间字段必须使用 timestamptz:" >&2
    echo "$invalid_time_columns" >&2
    exit 1
fi

echo "表结构验证通过:"
echo "- SQL 脚本已成功执行两次"
echo "- 外键数量: 0"
echo "- 所有 *_at 字段均为 timestamptz"
