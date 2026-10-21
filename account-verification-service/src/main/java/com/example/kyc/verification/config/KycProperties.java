package com.example.kyc.verification.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** All timing, topic names and pool sizing inputs are configuration. */
@ConfigurationProperties(prefix = "kyc")
public record KycProperties(Review review, Outbox outbox, IdentityProvider identityProvider, Topics topics,
                            Retry retry, Concurrency concurrency) {

    public record Review(Duration delayMin, Duration delayMax, Duration pollInterval, int pollBatchSize) {}

    public record Outbox(Duration relayInterval, int batchSize, Duration claimLease, Duration sendTimeout,
                         Duration ageAlertThreshold) {}

    /**
     * Invariant (enforced at startup): overall deadline &lt; read. The read timeout applies per call and connect
     * does not add to it, so a deadline at or above the read timeout can never fire -- the per-call timeout
     * kills the call first and the abandonment path stays unreachable and untested.
     */
    public record IdentityProvider(String baseUrl, Duration connectTimeout, Duration readTimeout,
                                   Duration overallDeadline) {
        public IdentityProvider {
            if (readTimeout != null && overallDeadline != null
                    && overallDeadline.compareTo(readTimeout) >= 0) {
                throw new IllegalArgumentException("kyc.identity-provider.overall-deadline (" + overallDeadline
                        + ") must be less than read-timeout (" + readTimeout + ")");
            }
        }
    }

    public record Topics(String request, String ack, String verdict) {}

    /** attempts=4 with exponential backoff gives (attempts-1) retry topics. */
    public record Retry(int attempts, long delayMs, long maxDelayMs) {}

    public record Concurrency(int main, int retry) {}
}
