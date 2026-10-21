package com.example.kyc.creation.outbox;

import java.time.Duration;

public final class RelayPolicy {

    private RelayPolicy() {}

    /**
     * Invariant: no row may still be in flight when its claim lapses. Stop sending once
     * elapsed time reaches lease − send-timeout; the remaining rows are left for a later tick.
     */
    public static boolean canSendMore(Duration elapsed, Duration lease, Duration sendTimeout) {
        return elapsed.compareTo(lease.minus(sendTimeout)) < 0;
    }
}
