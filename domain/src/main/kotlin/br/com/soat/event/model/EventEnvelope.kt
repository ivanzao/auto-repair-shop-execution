package br.com.soat.event.model

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

/**
 * Envelope de todo evento de saga, na forma serializada no corpo (SNS/SQS):
 * `{ eventId, eventType, eventVersion, occurredAt, payload }`.
 * `occurredAt` é ISO-8601 (Instant.toString()) para casar com o parse dos consumidores.
 */
data class EventEnvelope(
    val eventId: UUID = UUID.randomUUID(),
    val eventType: String,
    val eventVersion: Int = 1,
    val occurredAt: String = Instant.now().toString(),
    val payload: JsonNode,
    @get:JsonIgnore val traceparent: String? = null,
)
