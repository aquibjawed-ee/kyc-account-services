package com.example.kyc.verification.support;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.example.kyc.verification.messaging.Messages;
import com.example.kyc.verification.review.OutcomeSelector.PlannedOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Real PostgreSQL 16 (Zonky embedded binaries), a real KRaft Kafka broker (spring-kafka-test) and a WireMock
 * identity provider, all in-JVM: no Docker daemon needed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, bootstrapServersProperty = "spring.kafka.bootstrap-servers")
public abstract class IntegrationTestBase {

    protected static final String REQUEST = "kyc.request.v1";
    protected static final String ACK = "kyc.ack.v1";
    protected static final String VERDICT = "kyc.verdict.v1";
    protected static final String VERIFY_PATH = "/identity/v1/verify";

    static final EmbeddedPostgres POSTGRES;
    protected static final WireMockServer WIREMOCK = new WireMockServer(options().dynamicPort());

    static {
        try {
            POSTGRES = EmbeddedPostgres.builder().start();
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        WIREMOCK.start();
    }

    @DynamicPropertySource
    static void infraProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("kyc.identity-provider.base-url", WIREMOCK::baseUrl);
    }

    @Autowired protected EmbeddedKafkaBroker broker;

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected MeterRegistry metrics;
    @Autowired protected ObjectMapper mapper;
    @Autowired protected TestRestTemplate rest;
    @Autowired protected ForcedOutcomeSelector selector;

    protected KafkaTestSupport kafka() {
        return new KafkaTestSupport(broker.getBrokersAsString());
    }

    protected static String randomPan() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) sb.append((char) ('A' + r.nextInt(26)));
        for (int i = 0; i < 4; i++) sb.append(r.nextInt(10));
        return sb.append((char) ('A' + r.nextInt(26))).toString();
    }

    /** A request for a fresh application; persons are PRIMARY, NOMINEE, GUARANTOR with random PANs. */
    protected Messages.KycRequest newRequest() {
        return new Messages.KycRequest(UUID.randomUUID(), UUID.randomUUID(), Instant.now(), List.of(
                new Messages.PersonIdentity("PRIMARY", "Asha Rao", randomPan(), "234567890124"),
                new Messages.PersonIdentity("NOMINEE", "Ravi Rao", randomPan(), "345678901235"),
                new Messages.PersonIdentity("GUARANTOR", "Meera Rao", randomPan(), "456789012346")));
    }

    protected void publish(Messages.KycRequest request) {
        try {
            kafka().send(REQUEST, request.applicationId().toString(), mapper.writeValueAsString(request));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected String pan(Messages.KycRequest r, String role) {
        return r.persons().stream().filter(p -> p.role().equals(role)).findFirst().orElseThrow().pan();
    }

    protected void force(Messages.KycRequest r, PlannedOutcome outcome) {
        selector.force(r.applicationId(), outcome);
    }

    // ---- WireMock helpers (stubs are matched by PAN so tests stay independent) ----

    protected MappingBuilder verifyFor(String pan) {
        return post(urlEqualTo(VERIFY_PATH)).withRequestBody(matchingJsonPath("$.pan", equalTo(pan)));
    }

    protected void stubMatch(String pan) {
        WIREMOCK.stubFor(verifyFor(pan).willReturn(okJson("{\"match\":true,\"reason\":\"match\"}")));
    }

    protected void stubMatchAll(Messages.KycRequest r) {
        r.persons().forEach(p -> stubMatch(p.pan()));
    }

    protected void stubMismatch(String pan, String reason) {
        WIREMOCK.stubFor(verifyFor(pan).willReturn(okJson("{\"match\":false,\"reason\":\"" + reason + "\"}")));
    }

    protected void stubStatus(String pan, int status) {
        WIREMOCK.stubFor(verifyFor(pan).willReturn(aResponse().withStatus(status)));
    }

    protected int providerCalls(String pan) {
        return WIREMOCK.countRequestsMatching(
                postRequestedFor(urlEqualTo(VERIFY_PATH)).withRequestBody(matchingJsonPath("$.pan", equalTo(pan)))
                        .build()).getCount();
    }

    // ---- assertions helpers ----

    protected List<JsonNode> messages(String topic, UUID applicationId) {
        return kafka().values(topic, applicationId.toString()).stream().map(v -> {
            try {
                return mapper.readTree(v);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    protected JsonNode review(UUID applicationId) {
        return rest.getForEntity("/api/v1/reviews/" + applicationId, JsonNode.class).getBody();
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
