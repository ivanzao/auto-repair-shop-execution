package br.com.soat.execution

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import java.util.UUID

enum class TxResult { SUCCESS, STOCK_CONFLICT, DUPLICATE }

data class TxPut(
    val item: Map<String, AttributeValue>,
    val conditionExpression: String? = null,
    val expressionAttributeValues: Map<String, AttributeValue> = emptyMap(),
    val expressionAttributeNames: Map<String, String> = emptyMap(),
)

data class SupplyDecrement(val supplyId: UUID, val quantity: Int)

data class SupplyIncrement(val supplyId: UUID, val quantity: Int)

interface TransactionalWriter {
    fun writeAll(
        puts: List<TxPut>,
        decrements: List<SupplyDecrement> = emptyList(),
        increments: List<SupplyIncrement> = emptyList(),
    ): TxResult
}
