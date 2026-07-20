package br.com.soat.event

import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.s
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking

class ProcessedEventDynamoRepository(private val db: DynamoDb) : ProcessedEventRepository {

    override fun markProcessed(eventId: UUID, consumerId: String): Boolean = runBlocking {
        try {
            db.client.putItem(
                PutItemRequest {
                    tableName = db.tableName
                    item = mapOf(
                        "pk" to s(Keys.processed(eventId)),
                        "sk" to s(Keys.consumer(consumerId)),
                        "type" to s("PROC"),
                        "processedAt" to s(LocalDateTime.now().toString()),
                    )
                    conditionExpression = "attribute_not_exists(pk)"
                },
            )
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        }
    }

    override fun isProcessed(eventId: UUID, consumerId: String): Boolean = runBlocking {
        db.client.getItem(
            GetItemRequest {
                tableName = db.tableName
                key = mapOf("pk" to s(Keys.processed(eventId)), "sk" to s(Keys.consumer(consumerId)))
            },
        ).item != null
    }
}
