package br.com.soat.storage

import aws.sdk.kotlin.services.dynamodb.model.Put
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItem
import aws.sdk.kotlin.services.dynamodb.model.TransactWriteItemsRequest
import aws.sdk.kotlin.services.dynamodb.model.TransactionCanceledException
import aws.sdk.kotlin.services.dynamodb.model.Update
import br.com.soat.execution.SupplyDecrement
import br.com.soat.execution.SupplyIncrement
import br.com.soat.execution.TransactionalWriter
import br.com.soat.execution.TxPut
import br.com.soat.execution.TxResult
import kotlinx.coroutines.runBlocking

private const val CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed"

class DynamoTransactionalWriter(private val db: DynamoDb) : TransactionalWriter {

    override fun writeAll(
        puts: List<TxPut>,
        decrements: List<SupplyDecrement>,
        increments: List<SupplyIncrement>,
    ): TxResult = runBlocking {
        val items = buildList {
            puts.forEach { p ->
                add(
                    TransactWriteItem {
                        put = Put {
                            tableName = db.tableName
                            item = p.item
                            if (p.conditionExpression != null) {
                                conditionExpression = p.conditionExpression
                                if (p.expressionAttributeValues.isNotEmpty()) {
                                    expressionAttributeValues = p.expressionAttributeValues
                                }
                                if (p.expressionAttributeNames.isNotEmpty()) {
                                    expressionAttributeNames = p.expressionAttributeNames
                                }
                            }
                        }
                    },
                )
            }
            decrements.forEach { d ->
                add(
                    TransactWriteItem {
                        update = Update {
                            tableName = db.tableName
                            key = mapOf("pk" to s(Keys.supply(d.supplyId)), "sk" to s(Keys.supply(d.supplyId)))
                            updateExpression = "SET quantityInStock = quantityInStock - :q, version = version + :one"
                            conditionExpression = "quantityInStock >= :q"
                            expressionAttributeValues = mapOf(":q" to n(d.quantity), ":one" to n(1))
                        }
                    },
                )
            }
            increments.forEach { i ->
                add(
                    TransactWriteItem {
                        update = Update {
                            tableName = db.tableName
                            key = mapOf("pk" to s(Keys.supply(i.supplyId)), "sk" to s(Keys.supply(i.supplyId)))
                            updateExpression = "SET quantityInStock = quantityInStock + :q, version = version + :one"
                            expressionAttributeValues = mapOf(":q" to n(i.quantity), ":one" to n(1))
                        }
                    },
                )
            }
        }

        try {
            db.client.transactWriteItems(TransactWriteItemsRequest { transactItems = items })
            TxResult.SUCCESS
        } catch (e: TransactionCanceledException) {
            interpret(e, putsCount = puts.size, decrementsCount = decrements.size)
        }
    }

    private fun interpret(e: TransactionCanceledException, putsCount: Int, decrementsCount: Int): TxResult {
        val reasons = e.cancellationReasons.orEmpty()
        val putFailed = reasons.take(putsCount).any { it.code == CONDITIONAL_CHECK_FAILED }
        if (putFailed) return TxResult.DUPLICATE

        val decrementFailed = reasons
            .drop(putsCount).take(decrementsCount)
            .any { it.code == CONDITIONAL_CHECK_FAILED }
        if (decrementFailed) return TxResult.STOCK_CONFLICT

        throw e
    }
}
