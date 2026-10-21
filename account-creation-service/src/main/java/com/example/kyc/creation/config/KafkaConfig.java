package com.example.kyc.creation.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic requestTopic(KycProperties p) { return topic(p.topics().request()); }

    @Bean
    NewTopic ackTopic(KycProperties p) { return topic(p.topics().ack()); }

    @Bean
    NewTopic verdictTopic(KycProperties p) { return topic(p.topics().verdict()); }

    @Bean
    NewTopic ackDltTopic(KycProperties p) { return topic(p.topics().ack() + "-dlt"); }

    @Bean
    NewTopic verdictDltTopic(KycProperties p) { return topic(p.topics().verdict() + "-dlt"); }

    /** 3 attempts, fixed backoff, then dead-letter to {@code <topic>-dlt}. Picked up by Boot's default listener factory. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry registry,
                                         @Value("${kyc.consumer.retry-backoff:PT1S}") Duration backoff) {
        DeadLetterPublishingRecoverer publisher = new DeadLetterPublishingRecoverer(template,
                (rec, ex) -> new TopicPartition(rec.topic() + "-dlt", -1));
        ConsumerRecordRecoverer recoverer = (rec, ex) -> {
            log.error("event=kyc.dead_lettered topic={} key={} partition={} offset={}",
                    rec.topic(), rec.key(), rec.partition(), rec.offset(), ex);
            registry.counter("kyc.dlt.messages", "topic", rec.topic()).increment();
            publisher.accept(rec, ex);
        };
        // 1 delivery + 2 retries = 3 attempts; backoff is shortened by the test profile.
        return new DefaultErrorHandler(recoverer, new FixedBackOff(backoff.toMillis(), 2L));
    }
}
