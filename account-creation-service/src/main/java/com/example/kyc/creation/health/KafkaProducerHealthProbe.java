package com.example.kyc.creation.health;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.stereotype.Component;

/**
 * Active, time-bounded check that the brokers the Kafka producer is configured to use are reachable.
 *
 * <p>The producer exposes no "connected" flag: its connection-count metric is 0 before the first send and after
 * idle connections close, and {@code partitionsFor} reads cached metadata. So this probe asks the cluster
 * directly with an {@link AdminClient} built from the producer factory's own configuration (bootstrap servers
 * and any security settings), and treats "at least one node within the timeout" as up.
 */
@Component
public class KafkaProducerHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerHealthProbe.class);
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(1);

    private final ProducerFactory<String, String> producerFactory;
    private final Duration timeout;
    private volatile AdminClient admin;

    public KafkaProducerHealthProbe(ProducerFactory<String, String> producerFactory,
                                    @Value("${kyc.health.kafka-timeout:PT2S}") Duration timeout) {
        this.producerFactory = producerFactory;
        this.timeout = timeout;
    }

    /** {@code true} only when the producer's brokers answer with at least one node within the timeout. Never throws. */
    public boolean isUp() {
        try {
            Collection<Node> nodes = adminClient().describeCluster().nodes()
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (nodes.isEmpty()) {
                log.warn("event=health.kafka_down cause=no_nodes");
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("event=health.kafka_down cause={}", e.getClass().getSimpleName());
            return false;
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            log.warn("event=health.kafka_down cause={}", cause.getClass().getSimpleName());
            return false;
        }
    }

    private AdminClient adminClient() {
        AdminClient client = admin;
        if (client == null) {
            synchronized (this) {
                client = admin;
                if (client == null) {
                    client = AdminClient.create(adminConfig());
                    admin = client;
                }
            }
        }
        return client;
    }

    Map<String, Object> adminConfig() {
        Map<String, Object> config = new HashMap<>();
        producerFactory.getConfigurationProperties().forEach((key, value) -> {
            if (AdminClientConfig.configNames().contains(key)) {
                config.put(key, value);
            }
        });
        int timeoutMs = (int) Math.min(Integer.MAX_VALUE, timeout.toMillis());
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMs);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMs);
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, "account-creation-service-health");
        return config;
    }

    @PreDestroy
    void close() {
        AdminClient client = admin;
        if (client != null) {
            client.close(CLOSE_TIMEOUT);
        }
    }
}
