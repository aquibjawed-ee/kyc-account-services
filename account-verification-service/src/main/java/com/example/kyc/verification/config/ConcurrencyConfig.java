package com.example.kyc.verification.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Every pool is explicit and bounded; none ever falls back to running work on the caller's thread. */
@Configuration
public class ConcurrencyConfig {

    public static final int PERSONS_PER_APPLICATION = 3;

    /**
     * Sized from listener concurrency so every listener that can be mid-check has a thread per person:
     * (main concurrency + retry topics × retry concurrency) × 3. Zero queue, AbortPolicy: a full pool is
     * surfaced as "provider unavailable" and retried through the delayed retry topics.
     */
    public static int identityPoolSize(KycProperties p) {
        int retryTopics = p.retry().attempts() - 1;
        return (p.concurrency().main() + retryTopics * p.concurrency().retry()) * PERSONS_PER_APPLICATION;
    }

    @Bean(name = "identityCheckExecutor")
    ThreadPoolTaskExecutor identityCheckExecutor(KycProperties props, MeterRegistry registry) {
        int size = identityPoolSize(props);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(0); // SynchronousQueue
        executor.setThreadNamePrefix("identity-check-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy()); // never CallerRunsPolicy
        executor.initialize();
        new ExecutorServiceMetrics(executor.getThreadPoolExecutor(), "identityCheckExecutor", List.of())
                .bindTo(registry); // active + pool-size gauges
        return executor;
    }

    /** Retry-topic listeners run with their own (smaller) concurrency. */
    @Bean(name = "retryKafkaListenerContainerFactory")
    ConcurrentKafkaListenerContainerFactory<String, String> retryKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory, KycProperties props) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(props.concurrency().retry());
        return factory;
    }

    private static NewTopic topic(String name) {
        return TopicBuilder.name(name).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic requestTopic(KycProperties p) { return topic(p.topics().request()); }

    @Bean
    NewTopic ackTopic(KycProperties p) { return topic(p.topics().ack()); }

    @Bean
    NewTopic verdictTopic(KycProperties p) { return topic(p.topics().verdict()); }
}
