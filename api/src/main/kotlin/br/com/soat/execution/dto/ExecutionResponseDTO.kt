package br.com.soat.execution.dto

import br.com.soat.execution.model.Execution
import java.util.UUID

data class ExecutionResponseDTO(
    val orderId: UUID,
    val status: String,
    val reservationId: UUID?,
    val paymentId: String?,
    val createdAt: String,
    val modifiedAt: String,
) {
    companion object {
        fun from(execution: Execution) = ExecutionResponseDTO(
            orderId = execution.orderId,
            status = execution.status.name,
            reservationId = execution.reservationId,
            paymentId = execution.paymentId,
            createdAt = execution.createdAt.toString(),
            modifiedAt = execution.modifiedAt.toString(),
        )
    }
}
