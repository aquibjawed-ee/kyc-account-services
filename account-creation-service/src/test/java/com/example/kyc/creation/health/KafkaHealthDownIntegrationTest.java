package com.example.kyc.creation.health;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.kyc.creation.outbox.OutboxRelay;
import com.example.kyc.creation.support.ApiClient;
import com.example.kyc.creation.support.IntegrationTestBase;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * GET /health/kafka in a separate context whose producer (only) points at a port nothing listens on.
 * Admin, consumers and the DB still use the embedded broker and PostgreSQL, so the context starts normally.
 * Listeners and the outbox relay are off so this context never joins the shared consumer group or claims
 * outbox rows from the shared database; the context is closed when the class finishes.
 */
@TestPropertySource(properties = {
        "spring.kafka.listener.auto-startup=false",
        "kyc.outbox.relay-enabled=false",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "kyc.health.kafka-timeout=PT1S"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KafkaHealthDownIntegrationTest extends IntegrationTestBase {

    @DynamicPropertySource
    static void deadProducerBroker(DynamicPropertyRegistry registry) {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        registry.add("spring.kafka.producer.bootstrap-servers", () -> "localhost:" + port);
    }

    @Autowired ApplicationContext context;

    @Test
    void outboxRelayIsDisabledInThisContext() {
        assertThat(context.getBeanNamesForType(OutboxRelay.class)).isEmpty();
    }

    @Test
    void reportsDownWithinTheTimeoutWhenProducerBrokersAreUnreachable() {
        long start = System.nanoTime();
        ApiClient.Resp r = api().get("/health/kafka");
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertThat(r.status()).isEqualTo(503);
        assertThat(r.body().get("status").asText()).isEqualTo("DOWN");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
    }
}
