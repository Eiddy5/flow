-- FlowDraft 存量库迁移：以 company_id + flow_key 确定业务唯一性。
--
-- 适用范围：已经存在 flow_drafts 表的数据库。
-- 不是 gen/sql/flow/001_create_flow_tables.sql 的开发期重建基线。
--
-- 迁移会从 raw 的顶层 key: 回填 flow_key；无法回填或出现重复时整笔事务回滚。
-- 对无法自动回填的行，请先人工确认并补齐 flow_key 后再重新执行本脚本。

BEGIN;

-- 在回填、校验和建唯一约束期间阻止并发写入。
LOCK TABLE flow_drafts IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE flow_drafts
    ADD COLUMN IF NOT EXISTS flow_key varchar(128);

-- 只识别 YAML 顶层 key（必须从第 1 列开始），支持未引号、单引号和双引号值。
-- 不尝试从嵌套 task.key 推导 Flow key。
WITH parsed AS (
    SELECT
        id,
        company_id,
        COALESCE(
            (m_double)[1],
            (m_single)[1],
            btrim((m_plain)[1])
        ) AS extracted_key
    FROM (
        SELECT
            id,
            company_id,
            regexp_match(
                raw,
                $$^key[ \t]*:[ \t]*"([^"]*)"$$,
                'm'
            ) AS m_double,
            regexp_match(
                raw,
                $$^key[ \t]*:[ \t]*'([^']*)'$$,
                'm'
            ) AS m_single,
            regexp_match(
                raw,
                $$^key[ \t]*:[ \t]*([^#\r\n]*)$$,
                'm'
            ) AS m_plain
        FROM flow_drafts
        WHERE flow_key IS NULL
    ) source
)
UPDATE flow_drafts draft
SET flow_key = btrim(parsed.extracted_key)
FROM parsed
WHERE draft.id = parsed.id
  AND draft.company_id = parsed.company_id
  AND parsed.extracted_key IS NOT NULL;

-- 兼容已存在但带首尾空白的值。
UPDATE flow_drafts
SET flow_key = btrim(flow_key)
WHERE flow_key IS NOT NULL
  AND flow_key <> btrim(flow_key);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM flow_drafts
        WHERE flow_key IS NULL OR btrim(flow_key) = ''
    ) THEN
        RAISE EXCEPTION
            'flow_drafts contains rows without a recoverable flow_key';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM flow_drafts
        WHERE length(btrim(flow_key)) > 128
    ) THEN
        RAISE EXCEPTION
            'flow_drafts contains flow_key values longer than 128 characters';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM flow_drafts
        GROUP BY company_id, flow_key
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'flow_drafts contains duplicate (company_id, flow_key) values';
    END IF;
END
$$;

ALTER TABLE flow_drafts
    ALTER COLUMN flow_key SET NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'flow_drafts'::regclass
          AND conname = 'ck_flow_drafts_flow_key'
    ) THEN
        ALTER TABLE flow_drafts
            ADD CONSTRAINT ck_flow_drafts_flow_key
            CHECK (length(btrim(flow_key)) BETWEEN 1 AND 128);
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conrelid = 'flow_drafts'::regclass
          AND conname = 'uq_flow_drafts_company_flow_key'
    ) THEN
        ALTER TABLE flow_drafts
            ADD CONSTRAINT uq_flow_drafts_company_flow_key
            UNIQUE (company_id, flow_key);
    END IF;
END
$$;

COMMIT;
