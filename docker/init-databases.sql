-- One PostgreSQL instance, one logical database per service (no shared tables). Runs on a fresh volume only.
CREATE DATABASE kyc_creation;
CREATE DATABASE kyc_verification;
