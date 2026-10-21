package com.example.kyc.creation.outbox;

import com.example.kyc.creation.config.KycProperties;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** claim → (locks released) send → mark published. Never holds a DB lock across Kafka I/O. */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final KafkaTemplate<String, String> kafka;
    private final KycProperties.Outbox cfg;

    public OutboxRelay(OutboxStore store, KafkaTemplate<String, String> kafka, KycProperties props,
                       MeterRegistry registry) {
        this.store = store;
        this.kafka = kafka;
        this.cfg = props.outbox();
        Gauge.builder("kyc.outbox.oldest.age.seconds", store, s -> {
            try {
                return s.oldestUnsentAgeSeconds();
            } catch (RuntimeException e) {
                return Double.NaN;
            }
        }).register(registry);
    }

    @Scheduled(fixedDelayString = "${kyc.outbox.relay-interval}")
    public void tick() {
        List<OutboxRow> claimed;
        try {
            claimed = store.claim(cfg.batchSize(), cfg.claimLease());
        } catch (RuntimeException e) {
            log.error("event=outbox.claim_failed", e);
            return;
        }
        if (claimed.isEmpty()) return;

        long start = System.nanoTime();
        List<OutboxRow> sent = new ArrayList<>();
        for (OutboxRow row : claimed) {
            if (!RelayPolicy.canSendMore(Duration.ofNanos(System.nanoTime() - start), cfg.claimLease(),
                    cfg.sendTimeout())) {
                log.warn("event=outbox.batch_cut_short remaining_lease_budget_exhausted=true sent={} claimed={}",
                        sent.size(), claimed.size());
                break;
            }
            try {
                kafka.send(row.topic(), row.aggregateId().toString(), row.payload())
                        .get(cfg.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                sent.add(row);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                // Stop here to preserve order; the claim lapses and a later tick retries this row.
                log.warn("event=outbox.send_failed topic={} applicationId={} messageType={}",
                        row.topic(), row.aggregateId(), row.messageType(), e);
                break;
            }
        }
        if (sent.isEmpty()) return;
        try {
            store.markPublished(sent);
            log.info("event=outbox.published count={}", sent.size());
        } catch (RuntimeException e) {
            // Sent but unmarked: after the lease lapses the rows are re-sent; consumers dedupe.
            log.error("event=outbox.mark_failed count={} will_resend_after_lease=true", sent.size(), e);
        }
    }
}
