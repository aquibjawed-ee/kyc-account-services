package com.example.kyc.creation.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.creation.support.ApiClient;
import com.example.kyc.creation.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;

/** GET /health/kafka against the shared context, whose producer points at the embedded KRaft broker. */
class KafkaHealthUpIntegrationTest extends IntegrationTestBase {

    @Test
    void reportsUpWhenProducerBrokersAreReachable() {
        ApiClient.Resp r = api().get("/health/kafka");

        assertThat(r.status()).isEqualTo(200);
        assertThat(r.body().get("status").asText()).isEqualTo("UP");
    }

    @Test
    void existingEndpointsAreUnaffected() {
        assertThat(api().get("/actuator/health").status()).isEqualTo(200);
        assertThat(createApplication().get("kycStatus").asText()).isEqualTo("STARTED");
    }
}
