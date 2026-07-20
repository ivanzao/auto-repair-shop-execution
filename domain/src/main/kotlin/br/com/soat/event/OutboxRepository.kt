package br.com.soat.event

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import br.com.soat.event.model.EventEnvelope
import java.util.UUID

/**
 * Outbox transacional. `putItem` **monta e devolve** o item (não grava), para poder entrar num
 * `TransactWriteItems` junto com a mutação de negócio (ver TransactionalWriter). `save` grava isolado.
 */
interface OutboxRepository {
    fun putItem(env: EventEnvelope): Map<String, AttributeValue>
    fun save(env: EventEnvelope)
    fun pending(limit: Int): List<EventEnvelope>
    fun markPublished(eventId: UUID)
}
