package com.example.kyc.creation.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Test-only decorator: pass-through unless armed, in which case the next N mark steps fail (simulates a crash after send). */
@Component
@Primary
@Profile("test")
public class FaultInjectingOutboxStore implements OutboxStore {

    private final JdbcOutboxStore delegate;
    private final AtomicInteger failMarks = new AtomicInteger();
    private final AtomicInteger failed = new AtomicInteger();

    public FaultInjectingOutboxStore(JdbcOutboxStore delegate) {
        this.delegate = delegate;
    }

    public void failNextMarks(int n) {
        failMarks.set(n);
        if (n > 0) failed.set(0);
    }

    /** Number of mark steps failed since the fault was last armed. */
    public int failedMarks() {
        return failed.get();
    }

    @Override
    public void enqueue(UUID aggregateId, String topic, String messageType, String payload) {
        delegate.enqueue(aggregateId, topic, messageType, payload);
    }

    @Override
    public List<OutboxRow> claim(int batch, Duration lease) {
        return delegate.claim(batch, lease);
    }

    @Override
    public void markPublished(List<OutboxRow> rows) {
        if (failMarks.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            failed.incrementAndGet();
            throw new IllegalStateException("forced mark failure (test)");
        }
        delegate.markPublished(rows);
    }

    @Override
    public double oldestUnsentAgeSeconds() {
        return delegate.oldestUnsentAgeSeconds();
    }
}
