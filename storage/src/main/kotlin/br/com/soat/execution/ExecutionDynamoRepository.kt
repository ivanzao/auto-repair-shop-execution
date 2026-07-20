package br.com.soat.execution

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.s
import br.com.soat.storage.str
import br.com.soat.storage.strOrNull
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking

class ExecutionDynamoRepository(
    private val db: DynamoDb,
    private val mapper: ObjectMapper,
) : ExecutionRepository {

    override fun putItem(execution: Execution): Map<String, AttributeValue> = buildMap {
        put("pk", s(Keys.order(execution.orderId)))
        put("sk", s(Keys.order(execution.orderId)))
        put("gsi1pk", s(Keys.execStatus(execution.status.name)))
        put("gsi1sk", s(execution.createdAt.toString()))
        put("type", s("EXECUTION"))
        put("orderId", s(execution.orderId.toString()))
        put("status", s(execution.status.name))
        execution.reservationId?.let { put("reservationId", s(it.toString())) }
        execution.paymentId?.let { put("paymentId", s(it)) }
        put("orderSnapshot", s(mapper.writeValueAsString(execution.orderSnapshot)))
        put("createdAt", s(execution.createdAt.toString()))
        put("modifiedAt", s(execution.modifiedAt.toString()))
    }

    private fun Map<String, AttributeValue>.toExecution() = Execution(
        orderId = UUID.fromString(str("orderId")),
        status = ExecutionStatus.valueOf(str("status")),
        reservationId = strOrNull("reservationId")?.let(UUID::fromString),
        orderSnapshot = mapper.readTree(str("orderSnapshot")),
        paymentId = strOrNull("paymentId"),
        createdAt = Instant.parse(str("createdAt")),
        modifiedAt = Instant.parse(str("modifiedAt")),
    )

    override fun save(execution: Execution): Unit = runBlocking {
        db.client.putItem(PutItemRequest { tableName = db.tableName; item = putItem(execution) })
        Unit
    }

    override fun findByOrderId(orderId: UUID): Execution? = runBlocking {
        db.client.getItem(
            GetItemRequest {
                tableName = db.tableName
                key = mapOf("pk" to s(Keys.order(orderId)), "sk" to s(Keys.order(orderId)))
            },
        ).item?.toExecution()
    }

    override fun findByStatus(status: ExecutionStatus): List<Execution> = runBlocking {
        db.client.query(
            QueryRequest {
                tableName = db.tableName
                indexName = Keys.GSI
                keyConditionExpression = "gsi1pk = :pk"
                expressionAttributeValues = mapOf(":pk" to s(Keys.execStatus(status.name)))
            },
        ).items.orEmpty().map { it.toExecution() }
    }
}
