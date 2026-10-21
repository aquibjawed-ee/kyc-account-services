package com.example.kyc.creation.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Real PostgreSQL 16 (Zonky embedded binaries) and a real KRaft Kafka broker (spring-kafka-test), both in-JVM:
 * no Docker daemon needed. One of each per test JVM, shared by every integration test class.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class IntegrationTestBase {

    static final EmbeddedPostgres POSTGRES;

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired protected EmbeddedKafkaBroker broker;
    @LocalServerPort protected int port;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MeterRegistry metrics;
    @Autowired protected ObjectMapper mapper;

    protected ApiClient api() {
        return new ApiClient(port, mapper);
    }

    protected String bootstrap() {
        return broker.getBrokersAsString();
    }

    protected KafkaTestSupport kafka() {
        return new KafkaTestSupport(bootstrap());
    }

    /** Creates a fresh application and returns its response body. */
    protected JsonNode createApplication() {
        ApiClient.Resp r = api().post("/api/v1/accounts", Fixtures.createBody());
        if (r.status() != 201) throw new AssertionError("create failed: " + r.status() + " " + r.body());
        return r.body();
    }

    protected UUID idOf(JsonNode application) {
        return UUID.fromString(application.get("applicationId").asText());
    }

    protected String statusOf(UUID applicationId) {
        return api().get("/api/v1/accounts/" + applicationId).body().get("kycStatus").asText();
    }

    protected void awaitStatus(UUID applicationId, String expected) {
        await(() -> expected.equals(statusOf(applicationId)), "status " + expected + " for " + applicationId);
    }

    protected void await(Supplier<Boolean> condition, String what) {
        Awaitility.await(what).atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100))
                .until(condition::get);
    }

    protected double counter(String name, String... tags) {
        var c = metrics.find(name).tags(tags).counter();
        return c == null ? 0 : c.count();
    }
}
