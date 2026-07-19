package com.company.loopengine.shared.outbox;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(UUID id, String aggregateType, String aggregateId,
                          String eventType, String payloadJson, Instant occurredAt) {}
