package com.minh.banking.kafka;

import java.time.Instant;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public record EventEnvelope(UUID eventId,EventType eventType,int schemaVersion,Instant occurredAt,
        String correlationId,UUID aggregateId,JsonNode payload) {}
