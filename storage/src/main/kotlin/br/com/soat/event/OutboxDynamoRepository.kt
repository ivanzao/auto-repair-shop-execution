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
import br.com.soat.storage.strOrNull
import br.com.soat.tracing.Tracing
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kotlinx.coroutines.runBlocking

class OutboxDynamoRepository(
    private val db: DynamoDb,
    private val mapper: ObjectMapper,
) : OutboxRepository {

    override fun putItem(env: EventEnvelope): Map<String, AttributeValue> = buildMap {
        put("pk", s(Keys.outbox(env.eventId)))
        put("sk", s(Keys.outbox(env.eventId)))
        put("gsi1pk", s(Keys.OUTBOX_PENDING))
        put("gsi1sk", s(env.occurredAt))
        put("type", s("OUTBOX"))
        put("eventId", s(env.eventId.toString()))
        put("eventType", s(env.eventType))
        put("eventVersion", n(env.eventVersion))
        put("occurredAt", s(env.occurredAt))
        put("payload", s(mapper.writeValueAsString(env.payload)))
        val traceparent = env.traceparent ?: Tracing.currentTraceparent()
        if (traceparent != null) put("traceparent", s(traceparent))
    }

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
                traceparent = it.strOrNull("traceparent"),
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
