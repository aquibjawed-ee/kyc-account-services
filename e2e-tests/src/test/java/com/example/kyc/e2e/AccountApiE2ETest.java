package com.example.kyc.e2e;

import static com.example.kyc.e2e.StackFixture.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * The API contract as a client actually meets it, against the deployed service rather than MockMvc:
 * real serialisation, real validation, real status codes, real error bodies.
 */
@EnabledIf("com.example.kyc.e2e.StackFixture#stackIsUp")
@DisplayName("E2E: account API contract")
class AccountApiE2ETest {

    @Test
    @DisplayName("E2E-7: a malformed PAN and a bad Aadhaar checksum are both rejected, with no value echoed back")
    void validationRejectsBadIdentifiersWithoutLeakingThem() {
        String badPan = post(CREATION + "/api/v1/accounts", """
                {"primary":%s,"nominee":%s,"guarantor":%s}""".formatted(
                person("Asha", "notapan"), person("Ravi", randomPan()), person("Meera", randomPan())))
                .body().toString();
        assertThat(badPan).contains("VALIDATION_FAILED");

        // 234567890123 is well-formed but fails the Verhoeff checksum.
        Response badAadhaar = post(CREATION + "/api/v1/accounts", """
                {"primary":%s,"nominee":%s,"guarantor":%s}""".formatted(
                person("Asha", randomPan(), "234567890123"), person("Ravi", randomPan()),
                person("Meera", randomPan())));
        assertThat(badAadhaar.status()).isEqualTo(400);
        assertThat(badAadhaar.body().toString())
                .contains("VALIDATION_FAILED")
                .as("a rejected identifier must never be echoed back in the error")
                .doesNotContain("234567890123");
    }

    @Test
    @DisplayName("E2E-8: the three people on one application must have different PANs")
    void duplicatePanWithinOneApplicationIsRejected() {
        String shared = randomPan();
        Response res = createApplication(shared, shared, randomPan());

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().toString()).contains("DUPLICATE_PAN_IN_APPLICATION");
    }

    @Test
    @DisplayName("E2E-9: a second live application for the same PAN is refused, and says which one already exists")
    void secondLiveApplicationForSamePanConflictsAndIsRecoverable() {
        String pan = randomPan();
        Response first = createApplication(pan, randomPan(), randomPan());
        assertThat(first.status()).isEqualTo(201);

        Response second = createApplication(pan, randomPan(), randomPan());
        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().toString()).contains("ACTIVE_APPLICATION_EXISTS");

        // The whole point of carrying the id: a client whose POST timed out can find its application.
        assertThat(second.body().get("existingApplicationId").asText())
                .isEqualTo(first.text("applicationId"));
    }

    @Test
    @DisplayName("E2E-10: identity fields are immutable, everything else is editable")
    void patchAllowsNonIdentityFieldsOnly() {
        UUID id = UUID.fromString(createApplication().text("applicationId"));

        Response rejected = patch(CREATION + "/api/v1/accounts/" + id,
                """
                {"primary":{"pan":"%s"}}""".formatted(randomPan()));
        assertThat(rejected.status()).isEqualTo(400);
        assertThat(rejected.body().toString()).contains("IDENTITY_FIELD_IMMUTABLE");

        Response accepted = patch(CREATION + "/api/v1/accounts/" + id,
                """
                {"primary":{"address":"42 New Rd","annualIncome":1500000}}""");
        assertThat(accepted.status()).isEqualTo(200);
        assertThat(accepted.body().get("primary").get("address").asText()).isEqualTo("42 New Rd");

        // An edit must not restart or disturb the KYC already in flight.
        assertThat(statusOf(id)).isNotNull();
    }

    @Test
    @DisplayName("E2E-11: an empty patch body changes nothing and still succeeds")
    void emptyPatchIsANoOp() {
        UUID id = UUID.fromString(createApplication().text("applicationId"));
        String addressBefore = application(id).body().get("primary").get("address").asText();

        Response res = patch(CREATION + "/api/v1/accounts/" + id, "{}");

        assertThat(res.status()).isEqualTo(200);
        assertThat(application(id).body().get("primary").get("address").asText()).isEqualTo(addressBefore);
    }

    @Test
    @DisplayName("E2E-12: a soft-deleted application disappears from reads and frees its PAN")
    void softDeleteHidesTheApplicationAndReleasesThePan() {
        String pan = randomPan();
        UUID id = UUID.fromString(createApplication(pan, randomPan(), randomPan()).text("applicationId"));

        assertThat(delete(CREATION + "/api/v1/accounts/" + id).status()).isEqualTo(204);
        assertThat(application(id).status()).isEqualTo(404);

        // "Live" means not soft-deleted, so the same person can apply again.
        assertThat(createApplication(pan, randomPan(), randomPan()).status()).isEqualTo(201);
    }

    @Test
    @DisplayName("E2E-13: applications can be listed and filtered by KYC state")
    void listSupportsFilteringByState() {
        UUID id = UUID.fromString(createApplication().text("applicationId"));
        awaitUntil(() -> statusOf(id), s -> s != null, Duration.ofSeconds(30), "the application to be readable");

        Response all = get(CREATION + "/api/v1/accounts?size=100");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().get("content")).isNotEmpty();

        Response filtered = get(CREATION + "/api/v1/accounts?kycStatus=STARTED&size=100");
        assertThat(filtered.status()).isEqualTo(200);
        filtered.body().get("content").forEach(node ->
                assertThat(node.get("kycStatus").asText()).isEqualTo("STARTED"));
    }

    @Test
    @DisplayName("E2E-14: an unknown application and an unknown review both answer 404, not 500")
    void unknownIdsAreNotFoundRatherThanErrors() {
        UUID unknown = UUID.randomUUID();

        assertThat(application(unknown).status()).isEqualTo(404);

        Response review = review(unknown);
        assertThat(review.status()).isEqualTo(404);
        assertThat(review.body().toString()).contains("REVIEW_NOT_FOUND");
    }
}
