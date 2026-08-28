#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "用法: $0 <完整基线入口 SQL 文件>" >&2
    exit 2
fi

schema_entry_file="$1"
if [[ ! -f "$schema_entry_file" ]]; then
    echo "SQL 入口文件不存在: $schema_entry_file" >&2
    exit 2
fi

schema_entry_dir="$(cd "$(dirname "$schema_entry_file")" && pwd -P)"
schema_entry_name="$(basename "$schema_entry_file")"
schema_tables_dir="$schema_entry_dir/tables"
if [[ ! -d "$schema_tables_dir" ]]; then
    echo "表级 SQL 目录不存在: $schema_tables_dir" >&2
    exit 2
fi

schema_include_manifest="$(
    mktemp "${TMPDIR:-/tmp}/flow-schema-includes.XXXXXX"
)"
schema_source_tables="$(
    mktemp "${TMPDIR:-/tmp}/flow-schema-source-tables.XXXXXX"
)"
schema_database_tables="$(
    mktemp "${TMPDIR:-/tmp}/flow-schema-database-tables.XXXXXX"
)"
source_table_count=0

schema_container="postgres-schema-validate-$$"
schema_image="${POSTGRES_IMAGE:-postgres:17-alpine}"
schema_database="schema_validation"
schema_user="schema_validator"
schema_password="schema-validation"

cleanup_schema_container() {
    docker rm -f "$schema_container" >/dev/null 2>&1 || true
    rm -f \
        "$schema_include_manifest" \
        "$schema_source_tables" \
        "$schema_database_tables"
}

trap cleanup_schema_container EXIT

validate_table_sources() {
    if grep -Eq '^[[:space:]]*CREATE[[:space:]]+TABLE' \
        "$schema_entry_file"; then
        echo "基线入口只能编排表级脚本，不能直接包含 CREATE TABLE" >&2
        exit 1
    fi

    sed -nE \
        's|^[[:space:]]*\\ir[[:space:]]+tables/([^[:space:]]+\.sql)[[:space:]]*$|\1|p' \
        "$schema_entry_file" > "$schema_include_manifest"
    if [[ ! -s "$schema_include_manifest" ]]; then
        echo "基线入口没有包含任何 tables/*.sql" >&2
        exit 1
    fi

    for table_sql_file in "$schema_tables_dir"/*.sql; do
        if [[ ! -e "$table_sql_file" ]]; then
            continue
        fi

        source_table_count=$((source_table_count + 1))
        table_names="$(
            sed -nE \
                's/^[[:space:]]*CREATE[[:space:]]+TABLE[[:space:]]+IF[[:space:]]+NOT[[:space:]]+EXISTS[[:space:]]+([^[:space:]\(]+).*/\1/p' \
                "$table_sql_file"
        )"
        create_table_count="$(
            printf '%s\n' "$table_names" \
                | awk 'NF { count++ } END { print count + 0 }'
        )"
        if [[ "$create_table_count" != "1" ]]; then
            echo "每个表级 SQL 必须恰好包含一条 CREATE TABLE: $table_sql_file" >&2
            exit 1
        fi

        table_name="$table_names"
        if [[ ! "$table_name" =~ ^[a-z][a-z0-9]*(_[a-z][a-z0-9]*)*s$ ]]; then
            echo "表名必须为小写 snake_case，且最后单词以 s 结尾: $table_name" >&2
            exit 1
        fi
        printf '%s\n' "$table_name" >> "$schema_source_tables"

        table_file_stem="$(basename "$table_sql_file" .sql)"
        if [[ "$table_file_stem" != "$table_name" ]]; then
            echo "表级 SQL 文件名必须与表名一致: $table_sql_file -> $table_name" >&2
            exit 1
        fi

        include_count="$(
            grep -Fxc "$(basename "$table_sql_file")" \
                "$schema_include_manifest" || true
        )"
        if [[ "$include_count" != "1" ]]; then
            echo "基线入口必须恰好包含一次表级 SQL: $table_sql_file" >&2
            exit 1
        fi
    done

    if [[ "$source_table_count" == "0" ]]; then
        echo "表级 SQL 目录为空: $schema_tables_dir" >&2
        exit 1
    fi

    included_table_count="$(
        awk 'NF { count++ } END { print count + 0 }' \
            "$schema_include_manifest"
    )"
    if [[ "$included_table_count" != "$source_table_count" ]]; then
        echo "基线入口包含了重复或不存在的表级 SQL" >&2
        exit 1
    fi

    while IFS= read -r included_table_file; do
        if [[ ! -f "$schema_tables_dir/$included_table_file" ]]; then
            echo "基线入口引用的表级 SQL 不存在: $included_table_file" >&2
            exit 1
        fi
    done < "$schema_include_manifest"

    if grep -REn \
        'FOREIGN KEY|REFERENCES|CONSTRAINT[[:space:]]+fk_' \
        "$schema_tables_dir"; then
        echo "Flow 表结构不能创建 PostgreSQL 外键" >&2
        exit 1
    fi

    if grep -REin \
        'CHECK[[:space:]]*\(|EXCLUDE[[:space:]]|CREATE[[:space:]]+(TRIGGER|RULE|FUNCTION|PROCEDURE|DOMAIN)|CREATE[[:space:]]+TYPE[^;]*AS[[:space:]]+ENUM' \
        "$schema_tables_dir"; then
        echo "Flow 表结构不能创建数据库业务校验对象" >&2
        exit 1
    fi

    if grep -REin \
        '(^|[^[:alnum:]_])(date|time|timetz|timestamp|timestamptz|interval)([^[:alnum:]_]|$)' \
        "$schema_tables_dir"; then
        echo "Flow 时间字段必须使用 Unix 毫秒 bigint" >&2
        exit 1
    fi
}

validate_table_sources

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
    docker exec -e PGOPTIONS="-c client_min_messages=warning" \
        "$schema_container" \
        psql -X -v ON_ERROR_STOP=1 \
        -U "$schema_user" -d "$schema_database" \
        -f "/schema/$schema_entry_name" >/dev/null
}

docker exec "$schema_container" mkdir -p /schema
docker cp "$schema_entry_dir/." "$schema_container:/schema" >/dev/null

execute_schema_files
execute_schema_files

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

validation_constraint_count="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT count(*)
           FROM pg_constraint
          WHERE contype IN ('c', 'x')
            AND connamespace = 'public'::regnamespace;"
)"

if [[ "$validation_constraint_count" != "0" ]]; then
    echo "预期 CHECK/排他约束数量为 0，实际为: $validation_constraint_count" >&2
    exit 1
fi

validation_object_count="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT
             (SELECT count(*)
                FROM pg_trigger
               WHERE NOT tgisinternal)
           + (SELECT count(*)
                FROM pg_rules
               WHERE schemaname = 'public')
           + (SELECT count(*)
                FROM pg_type
               WHERE typnamespace = 'public'::regnamespace
                 AND typtype IN ('d', 'e'))
           + (SELECT count(*)
                FROM pg_proc
               WHERE pronamespace = 'public'::regnamespace);"
)"

if [[ "$validation_object_count" != "0" ]]; then
    echo "预期 Trigger、Rule、Function/Procedure、自定义 Domain/Enum 数量为 0，实际为: $validation_object_count" >&2
    exit 1
fi

invalid_time_columns="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT table_name || '.' || column_name || ':' || data_type
           FROM information_schema.columns
          WHERE table_schema = 'public'
            AND column_name ~ '(_at|_millis)$'
            AND data_type <> 'bigint'
          ORDER BY table_name, ordinal_position;"
)"

if [[ -n "$invalid_time_columns" ]]; then
    echo "时间点和毫秒时长字段必须使用 bigint:" >&2
    echo "$invalid_time_columns" >&2
    exit 1
fi

native_time_columns="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT table_name || '.' || column_name || ':' || data_type
           FROM information_schema.columns
          WHERE table_schema = 'public'
            AND data_type IN (
                'date',
                'time without time zone',
                'time with time zone',
                'timestamp without time zone',
                'timestamp with time zone',
                'interval'
            )
          ORDER BY table_name, ordinal_position;"
)"

if [[ -n "$native_time_columns" ]]; then
    echo "Flow Schema 不能使用 PostgreSQL 原生日期时间类型:" >&2
    echo "$native_time_columns" >&2
    exit 1
fi

invalid_table_names="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT tablename
           FROM pg_tables
          WHERE schemaname = 'public'
            AND tablename !~
                '^[a-z][a-z0-9]*(_[a-z][a-z0-9]*)*s$'
          ORDER BY tablename;"
)"

if [[ -n "$invalid_table_names" ]]; then
    echo "表名必须为小写 snake_case，且最后单词以 s 结尾:" >&2
    echo "$invalid_table_names" >&2
    exit 1
fi

database_table_count="$(
    docker exec "$schema_container" \
        psql -At -U "$schema_user" -d "$schema_database" -c \
        "SELECT count(*)
           FROM pg_tables
          WHERE schemaname = 'public';"
)"

if [[ "$database_table_count" != "$source_table_count" ]]; then
    echo "表级 SQL 数量与最终数据库表数量不一致: " \
        "$source_table_count != $database_table_count" >&2
    exit 1
fi

docker exec "$schema_container" \
    psql -At -U "$schema_user" -d "$schema_database" -c \
    "SELECT tablename
       FROM pg_tables
      WHERE schemaname = 'public'
      ORDER BY tablename;" > "$schema_database_tables"
sort -o "$schema_source_tables" "$schema_source_tables"

if ! cmp -s "$schema_source_tables" "$schema_database_tables"; then
    echo "表级 SQL 与最终数据库的表集合不一致:" >&2
    diff -u "$schema_source_tables" "$schema_database_tables" >&2 || true
    exit 1
fi

echo "表结构验证通过:"
echo "- 每张表由独立 SQL 文件定义并由单一入口编排"
echo "- 表名均为小写 snake_case，且最后单词以 s 结尾"
echo "- SQL 脚本已成功执行两次"
echo "- 外键数量: 0"
echo "- CHECK、排他约束、Trigger、Rule、Function/Procedure、自定义 Domain/Enum 数量: 0"
echo "- 所有 *_at 和 *_millis 字段均为 bigint，且没有原生日期时间字段"
