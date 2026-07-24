package br.com.soat.event.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class EventEnvelope(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String,
    val eventVersion: Int = 1,
    val occurredAt: String = Instant.now().toString(),
    val payload: JsonNode,
    @get:JsonIgnore val traceparent: String? = null,
)
