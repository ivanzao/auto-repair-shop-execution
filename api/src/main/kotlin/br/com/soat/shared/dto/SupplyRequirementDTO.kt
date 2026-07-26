package br.com.soat.shared.dto

import br.com.soat.shared.model.SupplyRequirement
import java.util.UUID

data class SupplyRequirementDTO(
    val supplyId: UUID,
    val quantity: Int,
) {

    fun toModel() = SupplyRequirement(
        supplyId = supplyId,
        quantity = quantity,
    )
}
