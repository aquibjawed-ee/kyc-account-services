package com.example.kyc.verification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxRelayPolicyTest {

    @Test
    void stopsSendingOnceALeaseMinusSendTimeoutHasElapsed() {
        Duration lease = Duration.ofSeconds(30);
        Duration sendTimeout = Duration.ofSeconds(5);
        assertThat(OutboxRelay.canSendMore(Duration.ZERO, lease, sendTimeout)).isTrue();
        assertThat(OutboxRelay.canSendMore(Duration.ofMillis(24_999), lease, sendTimeout)).isTrue();
        assertThat(OutboxRelay.canSendMore(Duration.ofSeconds(25), lease, sendTimeout)).isFalse();
    }
}
