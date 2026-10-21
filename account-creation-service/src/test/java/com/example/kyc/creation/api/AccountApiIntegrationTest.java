package com.example.kyc.creation.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.creation.support.ApiClient;
import com.example.kyc.creation.support.Fixtures;
import com.example.kyc.creation.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** AC: three-table persistence, CRUD with validation + structured errors, Flyway-only schema. */
class AccountApiIntegrationTest extends IntegrationTestBase {

    @Test
    void createPersistsAcrossThreeTablesAndReturnsStartedWithoutWaiting() {
        ObjectNode body = Fixtures.createBody();
        ApiClient.Resp r = api().post("/api/v1/accounts", body);

        assertThat(r.status()).isEqualTo(201);
        assertThat(r.location()).startsWith("/api/v1/accounts/");
        assertThat(r.body().get("kycStatus").asText()).isEqualTo("STARTED");
        UUID id = UUID.fromString(r.body().get("applicationId").asText());
        assertThat(r.body().get("primary").get("id").asText()).isNotBlank();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM kyc_details WHERE application_id = ?", Integer.class, id))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_relationship WHERE application_id = ?",
                Integer.class, id)).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT role FROM user_relationship WHERE application_id = ? ORDER BY role",
                String.class, id)).containsExactly("GUARANTOR", "NOMINEE", "PRIMARY");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM user_details u JOIN user_relationship r ON r.user_id = u.id
                WHERE r.application_id = ?""", Integer.class, id)).isEqualTo(3);
    }

    @Test
    void malformedPanAndAadhaarAreRejectedWithStructuredError() {
        ObjectNode badPan = Fixtures.createBody();
        ((ObjectNode) badPan.get("primary")).put("pan", "abcde1234f");
        ApiClient.Resp r1 = api().post("/api/v1/accounts", badPan);
        assertThat(r1.status()).isEqualTo(400);
        assertThat(r1.body().get("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(r1.body().get("errors").toString()).contains("primary.pan");
        assertThat(r1.raw()).doesNotContain("Exception").doesNotContain("at com.example");

        ObjectNode badAadhaar = Fixtures.createBody();
        String valid = badAadhaar.get("nominee").get("aadhaar").asText();
        char last = valid.charAt(11);
        String broken = valid.substring(0, 11) + (char) ('0' + ((last - '0' + 1) % 10)); // breaks the checksum
        ((ObjectNode) badAadhaar.get("nominee")).put("aadhaar", broken);
        ApiClient.Resp r2 = api().post("/api/v1/accounts", badAadhaar);
        assertThat(r2.status()).isEqualTo(400);
        assertThat(r2.body().get("errors").toString()).contains("nominee.aadhaar");

        ObjectNode shortAadhaar = Fixtures.createBody();
        ((ObjectNode) shortAadhaar.get("guarantor")).put("aadhaar", "12345");
        assertThat(api().post("/api/v1/accounts", shortAadhaar).status()).isEqualTo(400);
    }

    @Test
    void malformedJsonIsAStructuredBadRequest() {
        ApiClient.Resp r = api().postRaw("/api/v1/accounts", "{not json");
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void duplicatePanWithinOneApplicationIsRejected() {
        ObjectNode body = Fixtures.createBody();
        ((ObjectNode) body.get("nominee")).put("pan", body.get("primary").get("pan").asText());
        ApiClient.Resp r = api().post("/api/v1/accounts", body);
        assertThat(r.status()).isEqualTo(400);
        assertThat(r.body().get("code").asText()).isEqualTo("DUPLICATE_PAN_IN_APPLICATION");
    }

    @Test
    void repeatCreateReturnsConflictNamingTheExistingApplication() {
        String pan = Fixtures.randomPan();
        JsonNode first = api().post("/api/v1/accounts", Fixtures.createBody(pan)).body();
        ApiClient.Resp second = api().post("/api/v1/accounts", Fixtures.createBody(pan));

        assertThat(second.status()).isEqualTo(409);
        assertThat(second.body().get("code").asText()).isEqualTo("ACTIVE_APPLICATION_EXISTS");
        assertThat(second.body().get("existingApplicationId").asText()).isEqualTo(first.get("applicationId").asText());
    }

    @Test
    void getReturnsApplicationAndUnknownIdIsNotFound() {
        JsonNode created = createApplication();
        ApiClient.Resp ok = api().get("/api/v1/accounts/" + created.get("applicationId").asText());
        assertThat(ok.status()).isEqualTo(200);
        assertThat(ok.body().get("guarantor").get("pan").asText())
                .isEqualTo(created.get("guarantor").get("pan").asText());

        ApiClient.Resp missing = api().get("/api/v1/accounts/" + UUID.randomUUID());
        assertThat(missing.status()).isEqualTo(404);
        assertThat(missing.body().get("code").asText()).isEqualTo("APPLICATION_NOT_FOUND");
    }

    @Test
    void listFiltersByKycStatusAndExcludesSoftDeleted() {
        JsonNode keep = createApplication();
        JsonNode gone = createApplication();
        api().delete("/api/v1/accounts/" + gone.get("applicationId").asText());

        ApiClient.Resp all = api().get("/api/v1/accounts?size=100");
        assertThat(all.status()).isEqualTo(200);
        assertThat(all.body().get("content").toString()).contains(keep.get("applicationId").asText())
                .doesNotContain(gone.get("applicationId").asText());

        ApiClient.Resp done = api().get("/api/v1/accounts?kycStatus=KYC_DONE&size=100");
        assertThat(done.body().get("content").toString()).doesNotContain(keep.get("applicationId").asText());

        assertThat(api().get("/api/v1/accounts?kycStatus=NOPE").status()).isEqualTo(400);
    }

    @Test
    void patchUpdatesNonIdentityFieldsAndNeverRestartsKyc() {
        JsonNode created = createApplication();
        String id = created.get("applicationId").asText();

        ObjectNode patch = mapper.createObjectNode();
        patch.putObject("nominee").put("parentsName", "Sita Rao").put("annualIncome", 99.5);
        ApiClient.Resp r = api().patch("/api/v1/accounts/" + id, patch);

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("nominee").get("parentsName").asText()).isEqualTo("Sita Rao");
        assertThat(r.body().get("nominee").get("annualIncome").asDouble()).isEqualTo(99.5);
        assertThat(r.body().get("primary").get("parentsName").asText()).isEqualTo("Ravi Rao");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox WHERE aggregate_id = ?::uuid", Integer.class, id))
                .isEqualTo(1); // no second KYC request
    }

    @Test
    void patchRejectsIdentityFields() {
        String id = createApplication().get("applicationId").asText();
        for (String field : new String[] {"name", "pan", "aadhaar"}) {
            ObjectNode patch = mapper.createObjectNode();
            patch.putObject("primary").put(field, "X");
            ApiClient.Resp r = api().patch("/api/v1/accounts/" + id, patch);
            assertThat(r.status()).as(field).isEqualTo(400);
            assertThat(r.body().get("code").asText()).as(field).isEqualTo("IDENTITY_FIELD_IMMUTABLE");
        }
        ObjectNode unknown = mapper.createObjectNode();
        unknown.putObject("primary").put("favouriteColour", "blue");
        assertThat(api().patch("/api/v1/accounts/" + id, unknown).body().get("code").asText())
                .isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void deleteIsSoftAndFreesThePrimaryPan() {
        String pan = Fixtures.randomPan();
        String id = api().post("/api/v1/accounts", Fixtures.createBody(pan)).body().get("applicationId").asText();

        assertThat(api().delete("/api/v1/accounts/" + id).status()).isEqualTo(204);
        assertThat(api().get("/api/v1/accounts/" + id).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM kyc_details WHERE application_id = ?::uuid",
                Boolean.class, id)).isTrue();
        assertThat(api().delete("/api/v1/accounts/" + id).status()).isEqualTo(404);
        assertThat(api().post("/api/v1/accounts", Fixtures.createBody(pan)).status()).isEqualTo(201);
    }

    @Test
    void schemaIsCreatedByFlywayOnly() {
        assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("16");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success", String.class))
                .contains("1");
        assertThat(jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'""", String.class))
                .contains("user_details", "kyc_details", "user_relationship", "outbox", "processed_message");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname LIKE '%kyc_status%check%'",
                Integer.class)).isEqualTo(1);
    }
}
