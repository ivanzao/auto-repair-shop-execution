package br.com.soat.execution.repository

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import java.util.UUID

interface ExecutionRepository {
    fun findByOrderId(orderId: UUID): Execution?
    fun findByStatus(status: ExecutionStatus): List<Execution>

    /** Monta o item para entrar num TransactWriteItems (não grava). */
    fun putItem(execution: Execution): Map<String, AttributeValue>
    fun save(execution: Execution)
}
