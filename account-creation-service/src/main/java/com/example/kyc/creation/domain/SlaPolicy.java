package com.example.kyc.creation.domain;

import java.time.Duration;
import java.time.Instant;

/** SLA breach predicate. The SQL in the sweep mirrors this exactly. */
public final class SlaPolicy {

    private SlaPolicy() {}

    public static boolean isBreached(KycStatus status, Instant createdAt, Instant deletedAt,
                                     Instant alreadyBreachedAt, Instant now, Duration window) {
        return deletedAt == null
                && alreadyBreachedAt == null
                && !status.isFinal()
                && createdAt.isBefore(now.minus(window));
    }
}
