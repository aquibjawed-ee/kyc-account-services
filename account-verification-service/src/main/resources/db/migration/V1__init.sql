-- Verification service schema (owned entirely by Flyway). Shares no tables with the creation service.

CREATE TABLE review_record (
    review_id            uuid PRIMARY KEY,
    application_id       uuid NOT NULL UNIQUE,
    status               varchar(32) NOT NULL CHECK (status IN
        ('MANUAL_REVIEW_STARTED','MANUAL_REVIEW_FAILED','MANUAL_REVIEW_PASSED')),
    planned_outcome      varchar(16) NOT NULL CHECK (planned_outcome IN ('PASS','FAIL','NO_REPLY')),
    reason               text,
    verdict_due_at       timestamptz,
    verdict_published_at timestamptz,
    created_at           timestamptz NOT NULL
);
CREATE INDEX ix_review_due ON review_record (verdict_due_at)
    WHERE verdict_published_at IS NULL AND planned_outcome <> 'NO_REPLY';

CREATE TABLE outbox (
    id            uuid PRIMARY KEY,
    aggregate_id  uuid         NOT NULL,
    topic         varchar(100) NOT NULL,
    message_type  varchar(50)  NOT NULL,
    payload       jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL,
    seq           bigserial,
    claimed_until timestamptz,
    published_at  timestamptz
);
CREATE INDEX ix_outbox_unsent ON outbox (seq) WHERE published_at IS NULL;

CREATE TABLE processed_message (
    application_id uuid        NOT NULL,
    message_type   varchar(50) NOT NULL,
    processed_at   timestamptz NOT NULL,
    PRIMARY KEY (application_id, message_type)
);
