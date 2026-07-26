package br.com.soat.execution.model

import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.model.ExecutionStatus.AWAITING_DIAGNOSIS
import br.com.soat.execution.model.ExecutionStatus.CANCELED
import br.com.soat.execution.model.ExecutionStatus.COMPLETED
import br.com.soat.execution.model.ExecutionStatus.ENQUEUED
import br.com.soat.execution.model.ExecutionStatus.FAILED
import br.com.soat.execution.model.ExecutionStatus.IN_PROGRESS
import br.com.soat.execution.model.ExecutionStatus.RESERVED
import br.com.soat.shared.model.User
import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant
import java.util.UUID

data class Execution(
    val orderId: UUID,
    val status: ExecutionStatus,
    val reservationId: UUID? = null,
    val orderSnapshot: JsonNode,
    val diagnosedBy: User? = null,
    val paymentId: String? = null,
    val createdAt: Instant = Instant.now(),
    val modifiedAt: Instant = Instant.now(),
) {
    fun reserve(reservationId: UUID, diagnosedBy: User): Execution =
        transitionTo(RESERVED, from = setOf(AWAITING_DIAGNOSIS))
            .copy(reservationId = reservationId, diagnosedBy = diagnosedBy)

    fun enqueue(): Execution = transitionTo(ENQUEUED, from = setOf(RESERVED))
    fun start(): Execution = transitionTo(IN_PROGRESS, from = setOf(ENQUEUED))
    fun finish(): Execution = transitionTo(COMPLETED, from = setOf(IN_PROGRESS))
    fun fail(): Execution = transitionTo(FAILED, from = setOf(IN_PROGRESS))
    fun cancel(): Execution = transitionTo(CANCELED, from = setOf(AWAITING_DIAGNOSIS, RESERVED, ENQUEUED))

    fun withPayment(paymentId: String): Execution = copy(paymentId = paymentId)

    private fun transitionTo(target: ExecutionStatus, from: Set<ExecutionStatus>): Execution {
        if (status !in from) throw InvalidExecutionTransitionException(orderId, status, target)
        return copy(status = target, modifiedAt = Instant.now())
    }
}
