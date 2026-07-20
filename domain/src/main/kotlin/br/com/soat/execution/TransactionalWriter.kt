package br.com.soat.execution

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import java.util.UUID

/** Resultado de um TransactWriteItems interpretado a partir das razões de cancelamento. */
enum class TxResult { SUCCESS, STOCK_CONFLICT, DUPLICATE }

/** Put opcionalmente condicional (ex.: `attribute_not_exists(pk)` na Execution; `#st = ACTIVE` na Reservation). */
data class TxPut(
    val item: Map<String, AttributeValue>,
    val conditionExpression: String? = null,
    val expressionAttributeValues: Map<String, AttributeValue> = emptyMap(),
    val expressionAttributeNames: Map<String, String> = emptyMap(),
)

/** Decremento condicional de estoque (`quantityInStock >= :q`). */
data class SupplyDecrement(val supplyId: UUID, val quantity: Int)

/** Incremento incondicional de estoque (restauração numa compensação). */
data class SupplyIncrement(val supplyId: UUID, val quantity: Int)

/**
 * Escreve puts + deltas de estoque atomicamente (TransactWriteItems). Mapeia
 * `TransactionCanceledException` para [TxResult]: falha condicional num decremento → STOCK_CONFLICT;
 * falha condicional num put (guard de idempotência / status=ACTIVE) → DUPLICATE.
 */
interface TransactionalWriter {
    fun writeAll(
        puts: List<TxPut>,
        decrements: List<SupplyDecrement> = emptyList(),
        increments: List<SupplyIncrement> = emptyList(),
    ): TxResult
}
