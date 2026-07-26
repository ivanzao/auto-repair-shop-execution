package br.com.soat.execution.dto

import br.com.soat.execution.model.Execution
import br.com.soat.shared.dto.UserDTO
import java.util.UUID

data class ExecutionResponseDTO(
    val orderId: UUID,
    val status: String,
    val reservationId: UUID?,
    val diagnosedBy: UserDTO?,
    val paymentId: String?,
    val createdAt: String,
    val modifiedAt: String,
) {
    companion object {
        fun from(execution: Execution) = ExecutionResponseDTO(
            orderId = execution.orderId,
            status = execution.status.name,
            reservationId = execution.reservationId,
            diagnosedBy = execution.diagnosedBy?.let { UserDTO.from(it) },
            paymentId = execution.paymentId,
            createdAt = execution.createdAt.toString(),
            modifiedAt = execution.modifiedAt.toString(),
        )
    }
}
