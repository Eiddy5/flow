-- Flow 草稿与正式版本统一迁移
--
-- 适用范围：旧版同时存在 flows 和 flow_drafts 的数据库。
-- 目标结构：所有定义进入 flows，通过 draft=true/false 区分状态。
--
-- 重要：旧版已部署 flows 没有 source。执行前必须先准备每条已部署 Flow
-- 对应的原始 YAML；脚本发现 source 缺失或为空时会回滚，不会写入伪造内容。
-- 该脚本是存量库迁移，不是 gen/sql/flow 的空库重建基线。

\set ON_ERROR_STOP on

BEGIN;

SELECT EXISTS (
    SELECT 1
    FROM pg_class
    WHERE oid = to_regclass('public.flow_drafts')
      AND relkind = 'r'
) AS has_legacy_flow_drafts \gset

\if :has_legacy_flow_drafts

-- 阻止迁移期间的 Flow 写入；普通读取仍可继续。
LOCK TABLE flows, flow_drafts IN SHARE ROW EXCLUSIVE MODE;

-- 先以可空形式补齐新字段，完成回填和校验后再收紧 NOT NULL。
ALTER TABLE flows
    ADD COLUMN IF NOT EXISTS draft boolean,
    ADD COLUMN IF NOT EXISTS source text,
    ADD COLUMN IF NOT EXISTS lock_version bigint,
    ADD COLUMN IF NOT EXISTS status varchar(32);

ALTER TABLE flow_drafts
    ADD COLUMN IF NOT EXISTS flow_key varchar(128);

-- 兼容旧 flow_drafts 只有 raw、没有 flow_key 的数据库。
UPDATE flow_drafts draft
SET flow_key = btrim(parsed.extracted_key)
FROM (
    SELECT
        id,
        company_id,
        COALESCE(
            (regexp_match(raw, $$^key[ \t]*:[ \t]*"([^"]*)"$$, 'm'))[1],
            (regexp_match(raw, $$^key[ \t]*:[ \t]*'([^']*)'$$, 'm'))[1],
            btrim((regexp_match(
                raw,
                $$^key[ \t]*:[ \t]*([^#\r\n]*)$$,
                'm'
            ))[1])
        ) AS extracted_key
    FROM flow_drafts
    WHERE flow_key IS NULL OR btrim(flow_key) = ''
) parsed
WHERE draft.id = parsed.id
  AND draft.company_id = parsed.company_id
  AND parsed.extracted_key IS NOT NULL;

UPDATE flow_drafts
SET flow_key = btrim(flow_key)
WHERE flow_key IS NOT NULL
  AND flow_key <> btrim(flow_key);

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM flow_drafts
        WHERE flow_key IS NULL
           OR btrim(flow_key) = ''
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
        WHERE raw IS NULL OR length(btrim(raw)) = 0
    ) THEN
        RAISE EXCEPTION
            'flow_drafts contains empty raw YAML';
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

    IF EXISTS (
        SELECT 1
        FROM flows
        GROUP BY company_id, id
        HAVING count(*) > 1
    ) THEN
        RAISE EXCEPTION
            'flows contains duplicate (company_id, id) values';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM flows deployed
        JOIN flow_drafts draft
          ON draft.company_id = deployed.company_id
         AND draft.id = deployed.id
    ) THEN
        RAISE EXCEPTION
            'flows and flow_drafts contain colliding (company_id, id) values';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM flows
        WHERE source IS NULL OR length(btrim(source)) = 0
    ) THEN
        RAISE EXCEPTION
            'deployed flows require a backfilled non-empty source before migration';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM flows
        WHERE deleted IS TRUE
          AND (
              deleter IS NULL
              OR deleted_at IS NULL
              OR updater IS DISTINCT FROM deleter
              OR updated_at IS DISTINCT FROM deleted_at
          )
    ) THEN
        RAISE EXCEPTION
            'deleted flows do not satisfy the unified deletion audit invariant';
    END IF;
END
$$;

-- 旧主键包含 reversion，必须先拆除旧键和 reversion 检查，才能写入草稿行。
ALTER TABLE flows
    DROP CONSTRAINT IF EXISTS pk_flows,
    DROP CONSTRAINT IF EXISTS uq_flows_company_key_reversion,
    DROP CONSTRAINT IF EXISTS ck_flows_reversion,
    DROP CONSTRAINT IF EXISTS ck_flows_deleted;

-- 旧正式版本的 reversion 必须先允许 NULL，才能写入草稿行。
ALTER TABLE flows
    ALTER COLUMN reversion DROP NOT NULL;

-- 旧 flows 全部是正式版本；旧 deleted 布尔值转换为统一 Audit Status。
UPDATE flows
SET draft = false,
    lock_version = COALESCE(lock_version, 0),
    status = CASE
        WHEN deleted IS TRUE THEN 'Delete'
        ELSE COALESCE(NULLIF(btrim(status), ''), 'Open')
    END;

-- 将原始 YAML 草稿作为同一个 Flow 聚合写入 flows。
INSERT INTO flows (
    id,
    key,
    company_id,
    reversion,
    draft,
    source,
    lock_version,
    description,
    creator,
    updater,
    deleter,
    created_at,
    updated_at,
    deleted_at,
    inputs,
    outputs,
    variables,
    status
)
SELECT
    id,
    flow_key,
    company_id,
    NULL,
    true,
    raw,
    lock_version,
    '',
    creator,
    updater,
    deleter,
    created_at,
    updated_at,
    deleted_at,
    '[]'::jsonb,
    '[]'::jsonb,
    '{}'::jsonb,
    CASE
        WHEN deleted IS TRUE THEN 'Delete'
        ELSE 'Open'
    END
FROM flow_drafts;

ALTER TABLE flows
    ALTER COLUMN draft SET DEFAULT true,
    ALTER COLUMN draft SET NOT NULL,
    ALTER COLUMN source SET NOT NULL,
    ALTER COLUMN lock_version SET DEFAULT 0,
    ALTER COLUMN lock_version SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'Open',
    ALTER COLUMN status SET NOT NULL;

ALTER TABLE flows
    DROP COLUMN IF EXISTS deleted;

DO $$
BEGIN
    ALTER TABLE flows
        ADD CONSTRAINT pk_flows
        PRIMARY KEY (company_id, id);
EXCEPTION
    WHEN duplicate_object THEN NULL;
END
$$;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.flows'::regclass
          AND conname = 'ck_flows_definition_state'
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_definition_state
            CHECK (
                (draft AND reversion IS NULL)
                OR (NOT draft AND reversion > 0)
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.flows'::regclass
          AND conname = 'ck_flows_lock_version'
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_lock_version
            CHECK (lock_version >= 0);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.flows'::regclass
          AND conname = 'ck_flows_status'
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_status
            CHECK (
                length(btrim(status)) > 0
                AND status = btrim(status)
                AND (lower(status) <> 'delete' OR status = 'Delete')
            );
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.flows'::regclass
          AND conname = 'ck_flows_audit_time'
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_audit_time
            CHECK (updated_at >= created_at);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.flows'::regclass
          AND conname = 'ck_flows_deletion_audit'
    ) THEN
        ALTER TABLE flows
            ADD CONSTRAINT ck_flows_deletion_audit
            CHECK (
                (
                    status <> 'Delete'
                    AND deleter IS NULL
                    AND deleted_at IS NULL
                )
                OR (
                    status = 'Delete'
                    AND deleter IS NOT NULL
                    AND deleted_at IS NOT NULL
                    AND updater = deleter
                    AND updated_at = deleted_at
                )
            );
    END IF;
END
$$;

DROP INDEX IF EXISTS idx_flows_latest;

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_draft_key
    ON flows (company_id, key)
    WHERE draft;

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_deployed_key_reversion
    ON flows (company_id, key, reversion)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_latest_deployed
    ON flows (company_id, key, reversion DESC)
    WHERE NOT draft;

CREATE INDEX IF NOT EXISTS idx_flows_editable_drafts
    ON flows (company_id, key)
    WHERE draft AND status <> 'Delete';

CREATE INDEX IF NOT EXISTS idx_flows_creator_id
    ON flows (creator_id);

CREATE INDEX IF NOT EXISTS idx_flows_updater_id
    ON flows (updater_id);

CREATE INDEX IF NOT EXISTS idx_flows_deleter_id
    ON flows (deleter_id)
    WHERE deleter_id IS NOT NULL;

DROP TABLE flow_drafts;

\else

\echo 'flow_drafts does not exist; unified Flow migration is not required.'

\endif

COMMIT;
