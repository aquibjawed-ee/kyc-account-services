package com.example.kyc.creation.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All timing and topic names are configuration; nothing here is hard-coded in code paths. */
@ConfigurationProperties(prefix = "kyc")
public record KycProperties(Sla sla, Outbox outbox, Topics topics) {

    public record Sla(Duration window, Duration sweepInterval) {}

    public record Outbox(Duration relayInterval, int batchSize, Duration claimLease,
                         Duration sendTimeout, Duration ageAlertThreshold) {}

    public record Topics(String request, String ack, String verdict) {}
}
