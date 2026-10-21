package com.example.kyc.verification.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Kafka payloads (JSON). Shapes are published in contracts/kyc-messages-asyncapi.yaml. */
public final class Messages {

    public static final String KYC_REQUEST = "KYC_REQUEST";
    public static final String KYC_ACK = "KYC_ACK";
    public static final String KYC_VERDICT = "KYC_VERDICT";

    private Messages() {}

    public record PersonIdentity(String role, String name, String pan, String aadhaar) {}

    public record KycRequest(UUID messageId, UUID applicationId, Instant occurredAt, List<PersonIdentity> persons) {}

    public record KycAck(UUID messageId, UUID applicationId, UUID reviewId, Instant occurredAt) {}

    public enum VerdictOutcome { PASSED, FAILED }

    public record KycVerdict(UUID messageId, UUID applicationId, UUID reviewId, VerdictOutcome outcome,
                             String reason, Instant occurredAt) {}
}
