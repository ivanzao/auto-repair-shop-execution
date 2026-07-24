package br.com.soat.event

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import br.com.soat.event.model.EventEnvelope
import java.util.UUID

interface OutboxRepository {
    fun putItem(env: EventEnvelope): Map<String, AttributeValue>
    fun save(env: EventEnvelope)
    fun pending(limit: Int): List<EventEnvelope>
    fun markPublished(eventId: UUID)
}
