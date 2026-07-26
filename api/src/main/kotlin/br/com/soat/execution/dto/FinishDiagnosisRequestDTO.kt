package br.com.soat.execution.dto

import br.com.soat.execution.model.DiagnosisRequest
import br.com.soat.shared.model.SupplyRequirement
import java.util.UUID

data class FinishDiagnosisRequestDTO(
    val services: List<UUID> = emptyList(),
    val supplies: List<DiagnosisSupplyDTO> = emptyList(),
) {

    fun toModel() = DiagnosisRequest(
        services = services,
        supplies = supplies.map { SupplyRequirement(supplyId = it.id, quantity = it.quantity) },
    )
}

data class DiagnosisSupplyDTO(
    val id: UUID,
    val quantity: Int,
)
