package com.example.kyc.creation.support;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Plays the "other service" in tests: raw producer and a read-everything-from-the-start consumer. */
public class KafkaTestSupport {

    private final String bootstrap;

    public KafkaTestSupport(String bootstrap) {
        this.bootstrap = bootstrap;
    }

    public void send(String topic, String key, String value) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(p)) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
        } catch (Exception e) {
            throw new IllegalStateException("send failed", e);
        }
    }

    /** All values on {@code topic} with the given key, read from the beginning of every partition. */
    public List<String> values(String topic, String key) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        p.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> out = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p)) {
            var infos = consumer.partitionsFor(topic, Duration.ofSeconds(5));
            if (infos == null || infos.isEmpty()) return out;
            List<TopicPartition> partitions = infos.stream().map(i -> new TopicPartition(topic, i.partition())).toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            for (int i = 0; i < 50 && partitions.stream().anyMatch(tp -> consumer.position(tp) < end.get(tp)); i++) {
                for (ConsumerRecord<String, String> rec : consumer.poll(Duration.ofMillis(200))) {
                    if (key.equals(rec.key())) out.add(rec.value());
                }
            }
        }
        return out;
    }
}
