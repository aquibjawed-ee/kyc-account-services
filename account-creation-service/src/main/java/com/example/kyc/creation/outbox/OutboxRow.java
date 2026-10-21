package com.example.kyc.creation.outbox;

import java.util.UUID;

public record OutboxRow(UUID id, UUID aggregateId, String topic, String messageType, String payload, long seq) {}
