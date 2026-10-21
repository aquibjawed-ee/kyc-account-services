package com.example.kyc.verification.flow;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.verification.messaging.Messages;
import com.example.kyc.verification.review.OutcomeSelector.PlannedOutcome;
import com.example.kyc.verification.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * Verification-service half of the round trip over a real Kafka broker and PostgreSQL, with WireMock as the
 * external identity provider. The test plays the creation service: it produces kyc.request.v1 and reads
 * kyc.ack.v1 / kyc.verdict.v1.
 */
class VerificationFlowIntegrationTest extends IntegrationTestBase {

    private static final String DLT = REQUEST + "-dlt";

    @Test
    void passRoundTripAcknowledgesThenPublishesPassedVerdictWithReason() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.PASS);
        stubMatchAll(req);
        publish(req);

        await(() -> messages(ACK, req.applicationId()).size() == 1, "ack");
        await(() -> messages(VERDICT, req.applicationId()).size() == 1, "verdict");

        JsonNode ack = messages(ACK, req.applicationId()).get(0);
        JsonNode verdict = messages(VERDICT, req.applicationId()).get(0);
        assertThat(verdict.get("outcome").asText()).isEqualTo("PASSED");
        assertThat(verdict.get("reason").asText()).isEqualTo("Manual review passed");
        assertThat(verdict.get("reviewId").asText()).isEqualTo(ack.get("reviewId").asText());

        JsonNode review = review(req.applicationId());
        assertThat(review.get("status").asText()).isEqualTo("MANUAL_REVIEW_PASSED");
        assertThat(review.has("plannedOutcome")).isFalse(); // never exposed
        await(() -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ? AND published_at IS NOT NULL",
                Integer.class, req.applicationId()) == 2, "outbox rows marked published");
    }

    @Test
    void failRoundTripPublishesFailedVerdictWithHumanReadableReason() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.FAIL);
        stubMatchAll(req);
        publish(req);

        await(() -> messages(VERDICT, req.applicationId()).size() == 1, "verdict");
        JsonNode verdict = messages(VERDICT, req.applicationId()).get(0);
        assertThat(verdict.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(verdict.get("reason").asText()).isNotBlank().isNotEqualTo("Manual review passed");
        assertThat(review(req.applicationId()).get("status").asText()).isEqualTo("MANUAL_REVIEW_FAILED");
    }

    @Test
    void reviewIsStartedBeforeAnyVerdictAndDueTimeIsPersisted() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.PASS);
        stubMatchAll(req);
        publish(req);

        await(() -> jdbc.queryForObject("SELECT count(*) FROM review_record WHERE application_id = ?",
                Integer.class, req.applicationId()) == 1, "review persisted");
        assertThat(jdbc.queryForObject("SELECT verdict_due_at IS NOT NULL FROM review_record WHERE application_id = ?",
                Boolean.class, req.applicationId())).isTrue();
    }

    @Test
    void identityMismatchFailsImmediatelyWithAckAndFailedVerdict() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.PASS); // must be ignored: mismatch skips the simulated review
        stubMatch(pan(req, "PRIMARY"));
        stubMismatch(pan(req, "NOMINEE"), "PAN does not match name");
        stubMatch(pan(req, "GUARANTOR"));
        publish(req);

        await(() -> messages(ACK, req.applicationId()).size() == 1
                && messages(VERDICT, req.applicationId()).size() == 1, "ack and verdict (either order)");
        JsonNode verdict = messages(VERDICT, req.applicationId()).get(0);
        assertThat(verdict.get("outcome").asText()).isEqualTo("FAILED");
        assertThat(verdict.get("reason").asText()).startsWith("Identity mismatch for NOMINEE");
        assertThat(review(req.applicationId()).get("status").asText()).isEqualTo("MANUAL_REVIEW_FAILED");
    }

    @Test
    void noReplySubsetAcknowledgesButNeverPublishesAVerdict() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.NO_REPLY);
        stubMatchAll(req);
        publish(req);

        await(() -> messages(ACK, req.applicationId()).size() == 1, "ack");
        // Review delay in the test profile is <= 0.5s; hold for 1.5s to prove no verdict ever comes.
        Awaitility.await().during(Duration.ofMillis(1500)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(messages(VERDICT, req.applicationId())).isEmpty();
            assertThat(review(req.applicationId()).get("status").asText()).isEqualTo("MANUAL_REVIEW_STARTED");
        });
    }

    @Test
    void duplicateRequestDeliveryCreatesOneReviewAndOneAck() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.NO_REPLY);
        stubMatchAll(req);
        double before = counter("kyc.message.duplicate", "type", Messages.KYC_REQUEST);
        publish(req);
        await(() -> messages(ACK, req.applicationId()).size() == 1, "first ack");
        publish(req); // duplicate delivery

        await(() -> counter("kyc.message.duplicate", "type", Messages.KYC_REQUEST) >= before + 1, "duplicate skipped");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_record WHERE application_id = ?", Integer.class,
                req.applicationId())).isEqualTo(1);
        assertThat(messages(ACK, req.applicationId())).hasSize(1);
    }

    @Test
    void providerUnavailableRetriesThenDeadLettersWithoutAckOrReview() {
        Messages.KycRequest req = newRequest();
        stubStatus(pan(req, "PRIMARY"), 503);
        stubMatch(pan(req, "NOMINEE"));
        stubMatch(pan(req, "GUARANTOR"));
        double before = counter("kyc.dlt.messages", "topic", DLT);
        publish(req);

        await(() -> !kafka().values(DLT, req.applicationId().toString()).isEmpty(), "dead-letter record");
        await(() -> counter("kyc.dlt.messages", "topic", DLT) > before, "dlt counter");
        assertThat(providerCalls(pan(req, "PRIMARY"))).isEqualTo(4); // 4 attempts, then dead letter
        assertThat(messages(ACK, req.applicationId())).isEmpty();
        assertThat(messages(VERDICT, req.applicationId())).isEmpty();
        assertThat(rest.getForEntity("/api/v1/reviews/" + req.applicationId(), JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void providerFailingTwiceThenRecoveringSucceedsViaRetryTopics() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.NO_REPLY);
        String primary = pan(req, "PRIMARY");
        String scenario = "flaky-" + req.applicationId();
        WIREMOCK.stubFor(verifyFor(primary).inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("second"));
        WIREMOCK.stubFor(verifyFor(primary).inScenario(scenario).whenScenarioStateIs("second")
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        WIREMOCK.stubFor(verifyFor(primary).inScenario(scenario).whenScenarioStateIs("recovered")
                .willReturn(okJson("{\"match\":true,\"reason\":\"match\"}")));
        stubMatch(pan(req, "NOMINEE"));
        stubMatch(pan(req, "GUARANTOR"));
        publish(req);

        await(() -> messages(ACK, req.applicationId()).size() == 1, "ack after retries");
        assertThat(providerCalls(primary)).isEqualTo(3);
        assertThat(kafka().values(DLT, req.applicationId().toString())).isEmpty();
    }

    @Test
    void threeCallsAt60PercentOfTheDeadlineSucceedBecauseChecksRunInParallel() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.NO_REPLY);
        for (Messages.PersonIdentity p : req.persons()) { // 600ms each: sequential = 1.8s > 1s overall deadline
            WIREMOCK.stubFor(verifyFor(p.pan()).willReturn(okJson("{\"match\":true,\"reason\":\"match\"}")
                    .withFixedDelay(600)));
        }
        double abandonedBefore = counter("kyc.identity.abandoned", "reason", "deadline");
        publish(req);

        await(() -> messages(ACK, req.applicationId()).size() == 1, "ack");
        assertThat(counter("kyc.identity.abandoned", "reason", "deadline")).isEqualTo(abandonedBefore);
        assertThat(kafka().values(DLT, req.applicationId().toString())).isEmpty();
    }

    @Test
    void slowProviderCallIsAbandonedRetriedAndThenSucceeds() {
        Messages.KycRequest req = newRequest();
        force(req, PlannedOutcome.NO_REPLY);
        String primary = pan(req, "PRIMARY");
        String scenario = "slow-" + req.applicationId();
        WIREMOCK.stubFor(verifyFor(primary).inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"match\":true,\"reason\":\"match\"}").withFixedDelay(1200)) // > deadline (1s) < read (1.5s)
                .willSetStateTo("fast"));
        double abandonedBefore = counter("kyc.identity.abandoned", "reason", "deadline");
        WIREMOCK.stubFor(verifyFor(primary).inScenario(scenario).whenScenarioStateIs("fast")
                .willReturn(okJson("{\"match\":true,\"reason\":\"match\"}")));
        stubMatch(pan(req, "NOMINEE"));
        stubMatch(pan(req, "GUARANTOR"));
        publish(req);

        await(() -> messages(ACK, req.applicationId()).size() == 1, "ack after the slow call is retried");
        assertThat(providerCalls(primary)).isEqualTo(2);
        assertThat(counter("kyc.identity.abandoned", "reason", "deadline")).isEqualTo(abandonedBefore + 1);
        assertThat(counter("kyc.identity.abandoned", "reason", "pool_full")).isZero(); // interrupted threads were freed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM review_record WHERE application_id = ?", Integer.class,
                req.applicationId())).isEqualTo(1);
    }

    @Test
    void providerClientErrorIsNotRetriedAndGoesStraightToDeadLetter() {
        Messages.KycRequest req = newRequest();
        stubStatus(pan(req, "PRIMARY"), 400);
        stubMatch(pan(req, "NOMINEE"));
        stubMatch(pan(req, "GUARANTOR"));
        publish(req);

        await(() -> !kafka().values(DLT, req.applicationId().toString()).isEmpty(), "dead-letter record");
        assertThat(providerCalls(pan(req, "PRIMARY"))).isEqualTo(1);
        assertThat(messages(ACK, req.applicationId())).isEmpty();
    }

    @Test
    void malformedRequestGoesStraightToDeadLetter() {
        String key = UUID.randomUUID().toString();
        kafka().send(REQUEST, key, "{not json");
        await(() -> !kafka().values(DLT, key).isEmpty(), "dead-letter record");
    }

    @Test
    void unknownReviewIsStructuredNotFound() {
        var r = rest.getForEntity("/api/v1/reviews/" + UUID.randomUUID(), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(r.getBody().get("code").asText()).isEqualTo("REVIEW_NOT_FOUND");
    }

    @Test
    void schemaIsCreatedByFlywayOnly() {
        assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("16");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success", String.class))
                .contains("1");
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("review_record", "outbox", "processed_message")
                .doesNotContain("user_details", "kyc_details"); // creation tables are never shared
    }
}
