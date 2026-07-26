package br.com.soat.execution.dto

import br.com.soat.execution.model.MissingSupply
import java.util.UUID

data class SuppliesUnavailableResponseDTO(
    val code: String,
    val message: String,
    val missingSupplies: List<MissingSupplyDTO>,
) {
    companion object {
        fun from(missing: List<MissingSupply>) = SuppliesUnavailableResponseDTO(
            code = "SUPPLIES_UNAVAILABLE",
            message = "Insufficient stock for the requested supplies",
            missingSupplies = missing.map { MissingSupplyDTO(it.supplyId, it.name, it.requested, it.available) },
        )
    }
}

data class MissingSupplyDTO(
    val supplyId: UUID,
    val name: String,
    val requested: Int,
    val available: Int,
)
