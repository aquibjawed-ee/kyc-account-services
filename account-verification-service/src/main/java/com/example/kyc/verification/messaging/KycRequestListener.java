package com.example.kyc.verification.messaging;

import com.example.kyc.verification.identity.IdentityCheckCoordinator;
import com.example.kyc.verification.identity.IdentityProviderClient.IdentityResult;
import com.example.kyc.verification.identity.NonRetryableProviderException;
import com.example.kyc.verification.review.ReviewService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes KYC requests. Retryable failures (provider unavailable / deadline / full pool) go to non-blocking
 * delayed retry topics, then kyc.request.v1-dlt. 4xx and unparseable payloads go straight to the dead-letter topic.
 */
@Component
public class KycRequestListener {

    private static final Logger log = LoggerFactory.getLogger(KycRequestListener.class);

    private final ObjectMapper mapper;
    private final IdentityCheckCoordinator coordinator;
    private final ReviewService reviews;
    private final MeterRegistry metrics;

    public KycRequestListener(ObjectMapper mapper, IdentityCheckCoordinator coordinator, ReviewService reviews,
                              MeterRegistry metrics) {
        this.mapper = mapper;
        this.coordinator = coordinator;
        this.reviews = reviews;
        this.metrics = metrics;
    }

    @RetryableTopic(
            attempts = "${kyc.retry.attempts}",
            backoff = @Backoff(delayExpression = "${kyc.retry.delay-ms}", multiplier = 2.0,
                    maxDelayExpression = "${kyc.retry.max-delay-ms}"),
            listenerContainerFactory = "retryKafkaListenerContainerFactory",
            numPartitions = "3",
            replicationFactor = "1",
            exclude = {NonRetryableProviderException.class, NonRetryablePayloadException.class})
    @KafkaListener(topics = "${kyc.topics.request}", concurrency = "${kyc.concurrency.main}")
    public void onRequest(String payload) {
        Messages.KycRequest request = parse(payload);
        log.info("event=kyc.request_received applicationId={}", request.applicationId());
        if (reviews.alreadyProcessed(request.applicationId())) {
            metrics.counter("kyc.message.duplicate", "type", Messages.KYC_REQUEST).increment();
            log.debug("event=message.duplicate_skipped applicationId={}", request.applicationId());
            return;
        }
        // No DB transaction is open here; the listener waits once, bounded by the overall deadline.
        List<IdentityResult> results = coordinator.verifyAll(request.persons());
        reviews.recordReview(request, results);
    }

    @DltHandler
    public void onDeadLetter(String payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        metrics.counter("kyc.dlt.messages", "topic", topic).increment();
        log.error("event=kyc.dead_lettered topic={} payloadBytes={}", topic, payload == null ? 0 : payload.length());
    }

    private Messages.KycRequest parse(String payload) {
        try {
            Messages.KycRequest r = mapper.readValue(payload, Messages.KycRequest.class);
            if (r.applicationId() == null || r.persons() == null || r.persons().size() != 3) {
                throw new NonRetryablePayloadException("request must carry applicationId and exactly 3 persons");
            }
            return r;
        } catch (JsonProcessingException e) {
            // originalMessage only: the full Jackson message embeds a payload snippet (PAN/Aadhaar).
            throw new NonRetryablePayloadException("unparseable KYC request: " + e.getOriginalMessage());
        }
    }

    public static class NonRetryablePayloadException extends RuntimeException {
        public NonRetryablePayloadException(String message) { super(message); }

        public NonRetryablePayloadException(String message, Throwable cause) { super(message, cause); }
    }
}
