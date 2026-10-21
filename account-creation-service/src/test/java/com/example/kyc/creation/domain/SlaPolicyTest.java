package com.example.kyc.creation.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlaPolicyTest {

    private static final Instant NOW = Instant.parse("2026-01-02T12:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(24);

    @Test
    void pendingApplicationOlderThanWindowIsBreached() {
        assertThat(SlaPolicy.isBreached(KycStatus.MANUAL_REVIEW_PENDING, NOW.minus(Duration.ofHours(25)), null, null,
                NOW, WINDOW)).isTrue();
        assertThat(SlaPolicy.isBreached(KycStatus.STARTED, NOW.minus(Duration.ofHours(25)), null, null, NOW, WINDOW))
                .isTrue();
    }

    @Test
    void withinWindowOrExactlyAtBoundaryIsNotBreached() {
        assertThat(SlaPolicy.isBreached(KycStatus.IN_PROGRESS, NOW.minus(Duration.ofHours(23)), null, null, NOW, WINDOW))
                .isFalse();
        assertThat(SlaPolicy.isBreached(KycStatus.IN_PROGRESS, NOW.minus(WINDOW), null, null, NOW, WINDOW)).isFalse();
    }

    @Test
    void finalDeletedOrAlreadyFlaggedApplicationsAreNeverBreached() {
        Instant old = NOW.minus(Duration.ofDays(3));
        assertThat(SlaPolicy.isBreached(KycStatus.KYC_DONE, old, null, null, NOW, WINDOW)).isFalse();
        assertThat(SlaPolicy.isBreached(KycStatus.REVIEW_FAILED, old, null, null, NOW, WINDOW)).isFalse();
        assertThat(SlaPolicy.isBreached(KycStatus.IN_PROGRESS, old, NOW, null, NOW, WINDOW)).isFalse();
        assertThat(SlaPolicy.isBreached(KycStatus.IN_PROGRESS, old, null, NOW, NOW, WINDOW)).isFalse();
    }
}
