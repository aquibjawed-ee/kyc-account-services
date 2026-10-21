-- Creation service schema (owned entirely by Flyway; Hibernate is not used).

CREATE TABLE user_details (
    id            uuid PRIMARY KEY,
    name          varchar(200) NOT NULL,
    address       text         NOT NULL,
    pan           char(10)     NOT NULL,
    aadhaar       char(12)     NOT NULL,
    parents_name  varchar(200) NOT NULL,
    annual_income numeric(15,2) NOT NULL CHECK (annual_income >= 0),
    income_source varchar(100) NOT NULL,
    created_at    timestamptz  NOT NULL,
    updated_at    timestamptz  NOT NULL
);

-- One row per application: the application root.
CREATE TABLE kyc_details (
    application_id        uuid PRIMARY KEY,
    primary_pan           char(10)    NOT NULL,
    kyc_status            varchar(32) NOT NULL CHECK (kyc_status IN
        ('STARTED','IN_PROGRESS','MANUAL_REVIEW_PENDING','REVIEW_FAILED','KYC_DONE')),
    status_reason         text,
    review_id             uuid,
    sla_breach_detected_at timestamptz,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    deleted_at            timestamptz,
    version               bigint      NOT NULL
);

-- One live application per primary-applicant PAN (live = not soft-deleted).
CREATE UNIQUE INDEX ux_live_primary_pan ON kyc_details (primary_pan) WHERE deleted_at IS NULL;

CREATE TABLE user_relationship (
    application_id uuid        NOT NULL REFERENCES kyc_details (application_id),
    user_id        uuid        NOT NULL REFERENCES user_details (id),
    role           varchar(16) NOT NULL CHECK (role IN ('PRIMARY','NOMINEE','GUARANTOR')),
    PRIMARY KEY (application_id, role)
);

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
