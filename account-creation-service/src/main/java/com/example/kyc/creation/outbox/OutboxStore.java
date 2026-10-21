package com.example.kyc.creation.outbox;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Persistence side of the transactional outbox. Each method except {@code enqueue} owns a short transaction. */
public interface OutboxStore {

    String KYC_REQUEST = "KYC_REQUEST";

    /** Joins the caller's transaction, so the state change and the message commit together. */
    void enqueue(UUID aggregateId, String topic, String messageType, String payload);

    /** Transaction 1: lease up to {@code batch} unsent rows (skip-locked) and commit; no locks survive. */
    List<OutboxRow> claim(int batch, Duration lease);

    /** Transaction 2: mark sent rows published; STARTED→IN_PROGRESS for KYC_REQUEST rows (conditional). */
    void markPublished(List<OutboxRow> rows);

    /** Age in seconds of the oldest unsent message, or 0 when none. */
    double oldestUnsentAgeSeconds();
}
