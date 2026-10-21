package com.example.kyc.creation.domain;

/** Exactly the five KYC states from the brief. An SLA breach is a timestamp, never a state. */
public enum KycStatus {
    STARTED,
    IN_PROGRESS,
    MANUAL_REVIEW_PENDING,
    REVIEW_FAILED,
    KYC_DONE;

    public boolean isFinal() {
        return this == KYC_DONE || this == REVIEW_FAILED;
    }
}
