package com.example.kyc.creation.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RelayPolicyTest {

    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(5);

    @Test
    void keepsSendingWhileAWholeSendTimeoutStillFitsInTheLease() {
        assertThat(RelayPolicy.canSendMore(Duration.ZERO, LEASE, SEND_TIMEOUT)).isTrue();
        assertThat(RelayPolicy.canSendMore(Duration.ofMillis(24_999), LEASE, SEND_TIMEOUT)).isTrue();
    }

    @Test
    void stopsOnceElapsedReachesLeaseMinusSendTimeout() {
        assertThat(RelayPolicy.canSendMore(Duration.ofSeconds(25), LEASE, SEND_TIMEOUT)).isFalse();
        assertThat(RelayPolicy.canSendMore(Duration.ofSeconds(29), LEASE, SEND_TIMEOUT)).isFalse();
    }
}
