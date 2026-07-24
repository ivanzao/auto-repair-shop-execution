package br.com.soat.execution.model

import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.model.ExecutionStatus.CANCELED
import br.com.soat.execution.model.ExecutionStatus.COMPLETED
import br.com.soat.execution.model.ExecutionStatus.DIAGNOSED
import br.com.soat.execution.model.ExecutionStatus.FAILED
import br.com.soat.execution.model.ExecutionStatus.IN_PROGRESS
import br.com.soat.execution.model.ExecutionStatus.QUEUED
import br.com.soat.execution.model.ExecutionStatus.RESERVED
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class Execution(
    val orderId: UUID,
    val status: ExecutionStatus,
    val reservationId: UUID? = null,
    val orderSnapshot: JsonNode,
    val paymentId: String? = null,
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now(),
) {
    fun queue(): Execution = transitionTo(QUEUED, from = setOf(RESERVED))
    fun start(): Execution = transitionTo(IN_PROGRESS, from = setOf(QUEUED))
    fun finishDiagnosis(): Execution = transitionTo(DIAGNOSED, from = setOf(IN_PROGRESS))
    fun finish(): Execution = transitionTo(COMPLETED, from = setOf(DIAGNOSED))
    fun fail(): Execution = transitionTo(FAILED, from = setOf(IN_PROGRESS, DIAGNOSED))
    fun cancel(): Execution = transitionTo(CANCELED, from = setOf(RESERVED, QUEUED))

    fun withPayment(paymentId: String): Execution = copy(paymentId = paymentId)

    private fun transitionTo(target: ExecutionStatus, from: Set<ExecutionStatus>): Execution {
        if (status !in from) throw InvalidExecutionTransitionException(orderId, status, target)
        return copy(status = target, modifiedAt = Instant.now())
    }
}
