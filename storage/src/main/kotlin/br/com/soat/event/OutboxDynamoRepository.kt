package br.com.soat.event

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import br.com.soat.event.model.EventEnvelope
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.n
import br.com.soat.storage.s
import br.com.soat.storage.str
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kotlinx.coroutines.runBlocking

class OutboxDynamoRepository(
    private val db: DynamoDb,
    private val mapper: ObjectMapper,
) : OutboxRepository {

    override fun putItem(env: EventEnvelope): Map<String, AttributeValue> = mapOf(
        "pk" to s(Keys.outbox(env.eventId)),
        "sk" to s(Keys.outbox(env.eventId)),
        "gsi1pk" to s(Keys.OUTBOX_PENDING),
        "gsi1sk" to s(env.occurredAt),
        "type" to s("OUTBOX"),
        "eventId" to s(env.eventId.toString()),
        "eventType" to s(env.eventType),
        "eventVersion" to n(env.eventVersion),
        "occurredAt" to s(env.occurredAt),
        "payload" to s(mapper.writeValueAsString(env.payload)),
    )

    override fun save(env: EventEnvelope): Unit = runBlocking {
        db.client.putItem(PutItemRequest { tableName = db.tableName; item = putItem(env) })
        Unit
    }

    override fun pending(limit: Int): List<EventEnvelope> = runBlocking {
        db.client.query(
            QueryRequest {
                tableName = db.tableName
                indexName = Keys.GSI
                keyConditionExpression = "gsi1pk = :pk"
                expressionAttributeValues = mapOf(":pk" to s(Keys.OUTBOX_PENDING))
                this.limit = limit
            },
        ).items.orEmpty().map {
            EventEnvelope(
                eventId = UUID.fromString(it.str("eventId")),
                eventType = it.str("eventType"),
                eventVersion = (it["eventVersion"] as AttributeValue.N).value.toInt(),
                occurredAt = it.str("occurredAt"),
                payload = mapper.readTree(it.str("payload")),
            )
        }
    }

    override fun markPublished(eventId: UUID): Unit = runBlocking {
        db.client.updateItem(
            UpdateItemRequest {
                tableName = db.tableName
                key = mapOf("pk" to s(Keys.outbox(eventId)), "sk" to s(Keys.outbox(eventId)))
                updateExpression = "REMOVE gsi1pk, gsi1sk"
            },
        )
        Unit
    }
}
