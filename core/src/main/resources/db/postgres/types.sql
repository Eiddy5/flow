-- PostgreSQL types and expressions used by the Flow repositories.
DO $$
    BEGIN
        BEGIN
            CREATE TYPE state_type AS ENUM (
                'CREATED',
                'RUNNING',
                'PAUSED',
                'RESTARTED',
                'KILLING',
                'SUCCESS',
                'WARNING',
                'FAILED',
                'KILLED',
                'CANCELLED',
                'QUEUED',
                'RETRYING',
                'RETRIED',
                'SKIPPED',
                'BREAKPOINT',
                'SUBMITTED',
                'RESUBMITTED'
                );
        EXCEPTION
            WHEN duplicate_object THEN null;
        END;
    END;
$$;

DO $$
    BEGIN
        BEGIN
            CREATE TYPE log_level AS ENUM (
                'ERROR',
                'WARN',
                'INFO',
                'DEBUG',
                'TRACE'
                );
        EXCEPTION
            WHEN duplicate_object THEN null;
        END;
    END;
$$;

CREATE OR REPLACE FUNCTION FULLTEXT_REPLACE(text, text) RETURNS text
    LANGUAGE SQL
    IMMUTABLE
    RETURNS NULL ON NULL INPUT
RETURN TRIM(BOTH $2 FROM ARRAY_TO_STRING(
   ARRAY(
       SELECT DISTINCT *
       FROM UNNEST(REGEXP_SPLIT_TO_ARRAY(COALESCE($1, ''), '[^a-zA-Z\d]')) AS a
       WHERE a != ''
   ),
   $2
));

CREATE OR REPLACE FUNCTION FULLTEXT_INDEX(text) RETURNS tsvector
    LANGUAGE SQL
    IMMUTABLE
    RETURNS NULL ON NULL INPUT
    RETURN TO_TSVECTOR('simple', FULLTEXT_REPLACE($1, ' ')) || TO_TSVECTOR('simple', $1);

CREATE OR REPLACE FUNCTION FULLTEXT_SEARCH(text) RETURNS tsquery
    LANGUAGE SQL
    IMMUTABLE
    RETURNS NULL ON NULL INPUT
    RETURN CASE WHEN FULLTEXT_REPLACE($1, '') = '' THEN TO_TSQUERY('')
        ELSE TO_TSQUERY('simple', FULLTEXT_REPLACE($1, ':* & ') || ':*')
    END;

CREATE OR REPLACE FUNCTION STATE_FROMTEXT(text) RETURNS state_type
    LANGUAGE SQL
    IMMUTABLE
    RETURN CAST($1 AS state_type);

CREATE OR REPLACE FUNCTION LOGLEVEL_FROMTEXT(text) RETURNS log_level
    LANGUAGE SQL
    IMMUTABLE
    RETURN CAST($1 AS log_level);

CREATE OR REPLACE FUNCTION PARSE_ISO8601_DATETIME(text) RETURNS timestamptz
    LANGUAGE SQL
    IMMUTABLE
    RETURN $1::timestamptz;

CREATE OR REPLACE FUNCTION PARSE_ISO8601_TIMESTAMP(text) RETURNS int
    LANGUAGE SQL
    IMMUTABLE
    RETURN EXTRACT(epoch FROM $1::timestamptz AT TIME ZONE 'utc');

CREATE OR REPLACE FUNCTION PARSE_ISO8601_DURATION(text) RETURNS interval
    LANGUAGE SQL
    IMMUTABLE
    RETURN $1::interval;
