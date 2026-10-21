package com.example.kyc.creation.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.creation.outbox.FaultInjectingOutboxStore;
import com.example.kyc.creation.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Creation-service half of the round trip over a real Kafka broker and PostgreSQL. This test plays the
 * verification service by consuming kyc.request.v1 and producing kyc.ack.v1 / kyc.verdict.v1.
 */
class KycRoundTripIntegrationTest extends IntegrationTestBase {

    private static final String REQUEST = "kyc.request.v1";
    private static final String ACK = "kyc.ack.v1";
    private static final String VERDICT = "kyc.verdict.v1";

    @Autowired FaultInjectingOutboxStore faults;

    private String ack(UUID app, UUID review) throws Exception {
        return mapper.writeValueAsString(new Messages.KycAck(UUID.randomUUID(), app, review, Instant.now()));
    }

    private String verdict(UUID app, UUID review, Messages.VerdictOutcome outcome, String reason) throws Exception {
        return mapper.writeValueAsString(
                new Messages.KycVerdict(UUID.randomUUID(), app, review, outcome, reason, Instant.now()));
    }

    @Test
    void createReturnsStartedThenRequestIsRelayedAndStateMovesToInProgress() throws Exception {
        JsonNode created = createApplication();
        UUID id = idOf(created);
        assertThat(created.get("kycStatus").asText()).isEqualTo("STARTED"); // caller never waited

        await(() -> kafka().values(REQUEST, id.toString()).size() == 1, "request published");
        JsonNode msg = mapper.readTree(kafka().values(REQUEST, id.toString()).get(0));
        assertThat(msg.get("persons")).hasSize(3);
        assertThat(msg.get("persons").get(0).fieldNames()).toIterable()
                .containsExactlyInAnyOrder("role", "name", "pan", "aadhaar"); // minimisation invariant
        assertThat(msg.toString()).doesNotContain("parentsName").doesNotContain("annualIncome")
                .doesNotContain("address").doesNotContain("incomeSource");
        awaitStatus(id, "IN_PROGRESS");
    }

    @Test
    void ackThenPassVerdictEndsInKycDoneWithReason() throws Exception {
        UUID id = idOf(createApplication());
        UUID review = UUID.randomUUID();
        awaitStatus(id, "IN_PROGRESS");

        kafka().send(ACK, id.toString(), ack(id, review));
        awaitStatus(id, "MANUAL_REVIEW_PENDING");

        kafka().send(VERDICT, id.toString(), verdict(id, review, Messages.VerdictOutcome.PASSED, "Manual review passed"));
        awaitStatus(id, "KYC_DONE");
        JsonNode app = api().get("/api/v1/accounts/" + id).body();
        assertThat(app.get("statusReason").asText()).isEqualTo("Manual review passed");
        assertThat(jdbc.queryForObject("SELECT review_id FROM kyc_details WHERE application_id = ?", UUID.class, id))
                .isEqualTo(review);
    }

    @Test
    void failVerdictEndsInReviewFailedWithReason() throws Exception {
        UUID id = idOf(createApplication());
        UUID review = UUID.randomUUID();
        kafka().send(ACK, id.toString(), ack(id, review));
        awaitStatus(id, "MANUAL_REVIEW_PENDING");

        kafka().send(VERDICT, id.toString(),
                verdict(id, review, Messages.VerdictOutcome.FAILED, "Address proof inconsistent"));
        awaitStatus(id, "REVIEW_FAILED");
        assertThat(api().get("/api/v1/accounts/" + id).body().get("statusReason").asText())
                .isEqualTo("Address proof inconsistent");
    }

    @Test
    void verdictBeforeAckWinsAndLateAckIsIgnoredWithoutError() throws Exception {
        UUID id = idOf(createApplication());
        UUID review = UUID.randomUUID();
        kafka().send(VERDICT, id.toString(),
                verdict(id, review, Messages.VerdictOutcome.FAILED, "Identity mismatch for NOMINEE: PAN mismatch"));
        awaitStatus(id, "REVIEW_FAILED");

        double rejectedBefore = counter("kyc.transition.rejected");
        kafka().send(ACK, id.toString(), ack(id, review));
        await(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_message WHERE application_id = ? AND message_type = 'KYC_ACK'",
                Integer.class, id) == 1, "late ack consumed");
        assertThat(statusOf(id)).isEqualTo("REVIEW_FAILED");
        assertThat(counter("kyc.transition.rejected")).isEqualTo(rejectedBefore); // expected race, not an error
    }

    @Test
    void duplicateVerdictDeliveryChangesStateOnlyOnce() throws Exception {
        UUID id = idOf(createApplication());
        UUID review = UUID.randomUUID();
        double dupBefore = counter("kyc.message.duplicate", "type", "KYC_VERDICT");
        awaitStatus(id, "IN_PROGRESS"); // keep the version count deterministic (no STARTED race here)
        kafka().send(ACK, id.toString(), ack(id, review));
        awaitStatus(id, "MANUAL_REVIEW_PENDING");

        kafka().send(VERDICT, id.toString(), verdict(id, review, Messages.VerdictOutcome.PASSED, "Manual review passed"));
        kafka().send(VERDICT, id.toString(), verdict(id, review, Messages.VerdictOutcome.PASSED, "Manual review passed"));

        await(() -> counter("kyc.message.duplicate", "type", "KYC_VERDICT") >= dupBefore + 1, "duplicate skipped");
        assertThat(statusOf(id)).isEqualTo("KYC_DONE");
        assertThat(jdbc.queryForObject("SELECT version FROM kyc_details WHERE application_id = ?", Long.class, id))
                .isEqualTo(3L); // STARTED→IN_PROGRESS, →MANUAL_REVIEW_PENDING, →KYC_DONE: exactly three applied
    }

    @Test
    void lateVerdictForSoftDeletedApplicationIsDropped() throws Exception {
        UUID id = idOf(createApplication());
        api().delete("/api/v1/accounts/" + id);

        kafka().send(VERDICT, id.toString(),
                verdict(id, UUID.randomUUID(), Messages.VerdictOutcome.PASSED, "Manual review passed"));

        await(() -> jdbc.queryForObject(
                "SELECT count(*) FROM processed_message WHERE application_id = ? AND message_type = 'KYC_VERDICT'",
                Integer.class, id) == 1, "late verdict consumed");
        assertThat(jdbc.queryForObject("SELECT kyc_status FROM kyc_details WHERE application_id = ?", String.class, id))
                .isNotEqualTo("KYC_DONE");
    }

    @Test
    void noVerdictWithinSlaIsSurfacedAsBreachAndLateVerdictStillApplies() throws Exception {
        double before = counter("kyc.sla.breached");
        UUID id = idOf(createApplication());

        await(() -> !api().get("/api/v1/accounts/" + id).body().get("slaBreachDetectedAt").isNull(),
                "SLA breach recorded");
        JsonNode app = api().get("/api/v1/accounts/" + id).body();
        assertThat(app.get("kycStatus").asText()).isNotIn("KYC_DONE", "REVIEW_FAILED"); // still pending, not a sixth state
        assertThat(api().get("/api/v1/accounts?slaBreached=true&size=100").body().get("content").toString())
                .contains(id.toString());
        assertThat(counter("kyc.sla.breached")).isGreaterThan(before);

        kafka().send(VERDICT, id.toString(),
                verdict(id, UUID.randomUUID(), Messages.VerdictOutcome.PASSED, "Manual review passed"));
        awaitStatus(id, "KYC_DONE");
        assertThat(api().get("/api/v1/accounts/" + id).body().get("slaBreachDetectedAt").isNull()).isFalse();
    }

    @Test
    void failedMarkCausesResendThatIsAbsorbedByIdempotentConsumption() throws Exception {
        // The armed fault is global: drain rows left by earlier tests first, or their mark swallows it and
        // this application's request is never re-sent.
        await(() -> jdbc.queryForObject("SELECT count(*) FROM outbox WHERE published_at IS NULL", Integer.class) == 0,
                "outbox drained before arming the fault");
        faults.failNextMarks(1);
        UUID id;
        try {
            id = idOf(createApplication());
            await(() -> faults.failedMarks() >= 1, "mark failure injected");
        } finally {
            faults.failNextMarks(0);
        }

        // The send succeeded but the mark failed: after the claim lapses the same message is sent again.
        await(() -> kafka().values(REQUEST, id.toString()).size() >= 2, "request re-sent after lease lapse");
        List<String> sent = kafka().values(REQUEST, id.toString());
        assertThat(mapper.readTree(sent.get(0)).get("messageId")).isEqualTo(mapper.readTree(sent.get(1)).get("messageId"));
        awaitStatus(id, "IN_PROGRESS");

        // The downstream effect of a duplicate (two acks) is a single state change.
        UUID review = UUID.randomUUID();
        kafka().send(ACK, id.toString(), ack(id, review));
        kafka().send(ACK, id.toString(), ack(id, review));
        awaitStatus(id, "MANUAL_REVIEW_PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_message WHERE application_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void unparseableMessageIsDeadLetteredAndCounted() {
        double before = counter("kyc.dlt.messages", "topic", ACK);
        String key = "garbage-" + UUID.randomUUID();
        kafka().send(ACK, key, "{definitely not json");

        await(() -> !kafka().values(ACK + "-dlt", key).isEmpty(), "message on dead-letter topic");
        assertThat(counter("kyc.dlt.messages", "topic", ACK)).isGreaterThan(before);
    }
}
