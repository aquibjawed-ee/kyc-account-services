package com.example.kyc.creation.messaging;

import com.example.kyc.creation.service.KycTransitionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes acknowledgements and verdicts from the verification service. Payloads are parsed here (String
 * deserializer) so a malformed message fails the same way as any other error and ends up on the -dlt topic.
 */
@Component
public class KycResponseListeners {

    private static final Logger log = LoggerFactory.getLogger(KycResponseListeners.class);

    private final KycTransitionService transitions;
    private final ObjectMapper mapper;

    public KycResponseListeners(KycTransitionService transitions, ObjectMapper mapper) {
        this.transitions = transitions;
        this.mapper = mapper;
    }

    /** Never carry the Jackson exception (its message embeds a payload snippet, which may hold PAN/Aadhaar). */
    private <T> T parse(String payload, Class<T> type) {
        try {
            return mapper.readValue(payload, type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("unparseable " + type.getSimpleName() + ": " + e.getOriginalMessage());
        }
    }

    @KafkaListener(topics = "${kyc.topics.ack}")
    public void onAck(String payload) {
        Messages.KycAck ack = parse(payload, Messages.KycAck.class);
        log.info("event=kyc.ack_received applicationId={} reviewId={}", ack.applicationId(), ack.reviewId());
        transitions.onAck(ack);
    }

    @KafkaListener(topics = "${kyc.topics.verdict}")
    public void onVerdict(String payload) {
        Messages.KycVerdict verdict = parse(payload, Messages.KycVerdict.class);
        log.info("event=kyc.verdict_received applicationId={} outcome={}", verdict.applicationId(),
                verdict.outcome());
        transitions.onVerdict(verdict);
    }
}
