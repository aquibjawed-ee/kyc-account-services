package com.example.kyc.verification.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Persistence side of the transactional outbox (ack and verdict messages). */
public interface OutboxStore {

    record OutboxRow(UUID id, UUID aggregateId, String topic, String messageType, String payload, long seq) {}

    /** Joins the caller's transaction, so the state change and its message commit together. */
    void enqueue(UUID aggregateId, String topic, String messageType, String payload);

    /** Short transaction: lease up to {@code batch} unsent rows (skip-locked) and commit. */
    List<OutboxRow> claim(int batch, Duration lease);

    /** Short transaction: mark sent rows published. */
    void markPublished(List<OutboxRow> rows);

    double oldestUnsentAgeSeconds();
}
