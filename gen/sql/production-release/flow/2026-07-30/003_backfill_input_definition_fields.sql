BEGIN;

UPDATE flows
SET inputs = (
    SELECT jsonb_agg(
        CASE
            WHEN jsonb_typeof(item.value) = 'object' THEN
                item.value
                || CASE
                    WHEN NOT (item.value ? 'displayName')
                        AND NULLIF(
                            btrim(item.value ->> 'key'),
                            ''
                        ) IS NOT NULL
                    THEN jsonb_build_object(
                        'displayName',
                        item.value ->> 'key'
                    )
                    ELSE '{}'::jsonb
                END
                || CASE
                    WHEN NOT (item.value ? 'required')
                    THEN jsonb_build_object('required', false)
                    ELSE '{}'::jsonb
                END
            ELSE item.value
        END
        ORDER BY item.ordinality
    )
    FROM jsonb_array_elements(flows.inputs)
        WITH ORDINALITY AS item(value, ordinality)
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(flows.inputs) AS item(value)
    WHERE jsonb_typeof(item.value) = 'object'
      AND (
          (
              NOT (item.value ? 'displayName')
              AND NULLIF(btrim(item.value ->> 'key'), '') IS NOT NULL
          )
          OR NOT (item.value ? 'required')
      )
);

UPDATE flow_tasks
SET inputs = (
    SELECT jsonb_agg(
        CASE
            WHEN jsonb_typeof(item.value) = 'object' THEN
                item.value
                || CASE
                    WHEN NOT (item.value ? 'displayName')
                        AND NULLIF(
                            btrim(item.value ->> 'key'),
                            ''
                        ) IS NOT NULL
                    THEN jsonb_build_object(
                        'displayName',
                        item.value ->> 'key'
                    )
                    ELSE '{}'::jsonb
                END
                || CASE
                    WHEN NOT (item.value ? 'required')
                    THEN jsonb_build_object('required', false)
                    ELSE '{}'::jsonb
                END
            ELSE item.value
        END
        ORDER BY item.ordinality
    )
    FROM jsonb_array_elements(flow_tasks.inputs)
        WITH ORDINALITY AS item(value, ordinality)
)
WHERE EXISTS (
    SELECT 1
    FROM jsonb_array_elements(flow_tasks.inputs) AS item(value)
    WHERE jsonb_typeof(item.value) = 'object'
      AND (
          (
              NOT (item.value ? 'displayName')
              AND NULLIF(btrim(item.value ->> 'key'), '') IS NOT NULL
          )
          OR NOT (item.value ? 'required')
      )
);

COMMIT;
