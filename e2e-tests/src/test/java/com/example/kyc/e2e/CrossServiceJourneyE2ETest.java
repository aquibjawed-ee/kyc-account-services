package com.example.kyc.e2e;

import static com.example.kyc.e2e.StackFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.e2e.StackFixture.RawResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The journey that neither module's own suite can prove: a request leaving the creation service,
 * being adjudicated by a separate process, and the answer coming back. Each module's integration
 * tests play the other side themselves, so until now the round trip was only ever verified in two
 * halves against a stand-in.
 */
@EnabledIf("com.example.kyc.e2e.StackFixture#stackIsUp")
@DisplayName("E2E: creation service <-> Kafka <-> verification service")
class CrossServiceJourneyE2ETest {

    @Test
    @DisplayName("E2E-1: create returns at once, and the request crosses to the other service")
    void createIsNonBlockingAndReachesVerification() {
        Instant before = Instant.now();
        Response created = createApplication();
        Duration elapsed = Duration.between(before, Instant.now());

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.text("kycStatus")).isEqualTo("STARTED");
        assertThat(created.text("statusReason")).isNull();
        // The caller must never wait on verification, which takes 1-2 hours in the real profile.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));

        UUID id = UUID.fromString(created.text("applicationId"));
        Response review = awaitReview(id);
        assertThat(review.text("applicationId")).isEqualTo(id.toString());
        assertThat(review.text("status")).isEqualTo("MANUAL_REVIEW_STARTED");
        // The due time is persisted, which is what lets the delay survive a restart.
        assertThat(review.text("verdictDueAt")).isNotNull();
        assertThat(review.text("verdictPublishedAt")).isNull();
    }

    @Test
    @DisplayName("E2E-2: the acknowledgement travels back and advances the creation-side state")
    void acknowledgementCrossesBack() {
        UUID id = UUID.fromString(createApplication().text("applicationId"));

        String status = awaitUntil(() -> statusOf(id),
                s -> "MANUAL_REVIEW_PENDING".equals(s) || "KYC_DONE".equals(s) || "REVIEW_FAILED".equals(s),
                Duration.ofSeconds(60),
                "the acknowledgement to come back from the verification service for " + id);

        // Proves the second service produced a message that the first one consumed and applied.
        assertThat(status).isIn("MANUAL_REVIEW_PENDING", "KYC_DONE", "REVIEW_FAILED");
    }

    @Test
    @DisplayName("E2E-3: an identity mismatch fails the application with the offending role, both sides agreeing")
    void identityMismatchShortCircuitsAcrossBothServices() {
        Response created = createApplication(randomPan(), RESERVED_MISMATCH_PAN, randomPan());
        UUID id = UUID.fromString(created.text("applicationId"));

        String status = awaitUntil(() -> statusOf(id), "REVIEW_FAILED"::equals, Duration.ofSeconds(60),
                "the mismatch verdict to come back for " + id);
        assertThat(status).isEqualTo("REVIEW_FAILED");

        // The reason names the role, and it reached the creation service over Kafka.
        String reason = application(id).text("statusReason");
        assertThat(reason).contains("NOMINEE");

        // The verification side independently recorded the same outcome.
        assertThat(review(id).text("status")).isEqualTo("MANUAL_REVIEW_FAILED");

        // A mismatch skips the simulated review entirely rather than waiting it out.
        assertThat(application(id).text("slaBreachDetectedAt")).isNull();
    }

    @Test
    @DisplayName("E2E-4: a verdict reaches a terminal state with a reason (needs compressed timings)")
    @EnabledIf("com.example.kyc.e2e.StackFixture#verdictsArriveQuickly")
    void verdictCompletesTheRoundTrip() {
        UUID id = UUID.fromString(createApplication().text("applicationId"));

        String status = awaitUntil(() -> statusOf(id),
                s -> "KYC_DONE".equals(s) || "REVIEW_FAILED".equals(s), Duration.ofSeconds(120),
                "a terminal verdict for " + id);

        assertThat(status).isIn("KYC_DONE", "REVIEW_FAILED");
        assertThat(application(id).text("statusReason"))
                .as("every verdict carries a human-readable reason").isNotBlank();

        Response review = review(id);
        assertThat(review.text("status")).isIn("MANUAL_REVIEW_PASSED", "MANUAL_REVIEW_FAILED");
        assertThat(review.text("verdictPublishedAt")).isNotNull();

        // The two services agree on the outcome.
        boolean passedOnCreationSide = "KYC_DONE".equals(status);
        boolean passedOnVerificationSide = "MANUAL_REVIEW_PASSED".equals(review.text("status"));
        assertThat(passedOnCreationSide).isEqualTo(passedOnVerificationSide);
    }

    @Test
    @DisplayName("E2E-5: a silent review is surfaced as an SLA breach, not left pending forever")
    @EnabledIf("com.example.kyc.e2e.StackFixture#verdictsArriveQuickly")
    void noReplyIsSurfacedAsAnSlaBreach() {
        // Roughly 1 in 20 applications is deliberately never answered, so a batch is needed to
        // land on one. The outcome is a deterministic hash of the application id, not a coin flip.
        int batch = 40;
        UUID[] ids = new UUID[batch];
        for (int i = 0; i < batch; i++) {
            ids[i] = UUID.fromString(createApplication().text("applicationId"));
        }

        UUID breached = awaitUntil(() -> {
            for (UUID id : ids) {
                if (application(id).text("slaBreachDetectedAt") != null) return id;
            }
            return null;
        }, java.util.Objects::nonNull, Duration.ofSeconds(180),
                "at least one of " + batch + " applications to be flagged as an SLA breach");

        // The breach is recorded alongside the state, never as a sixth enum value.
        assertThat(statusOf(breached)).isNotIn("KYC_DONE", "REVIEW_FAILED");
        assertThat(review(breached).text("status")).isEqualTo("MANUAL_REVIEW_STARTED");

        // And it is findable through the documented filter.
        Response list = get(CREATION + "/api/v1/accounts?slaBreached=true&size=100");
        assertThat(list.body().get("content").toString()).contains(breached.toString());
    }

    @Test
    @DisplayName("E2E-6: both services publish an OpenAPI description and serve Swagger UI")
    void bothServicesExposeTheirApiDescription() {
        for (String base : new String[] {CREATION, VERIFICATION}) {
            Response apiDocs = get(base + "/v3/api-docs");
            assertThat(apiDocs.status()).as("OpenAPI description at " + base).isEqualTo(200);
            assertThat(apiDocs.text("openapi")).startsWith("3.");
            assertThat(apiDocs.body().get("info").get("title").asText()).isNotBlank();
            assertThat(apiDocs.body().get("paths")).isNotEmpty();

            // Swagger UI is HTML, not JSON, so it needs the raw fetch.
            RawResponse ui = getRaw(base + "/swagger-ui/index.html");
            assertThat(ui.status()).as("Swagger UI served at " + base).isEqualTo(200);
            assertThat(ui.body()).contains("swagger-ui");
        }
    }
}
