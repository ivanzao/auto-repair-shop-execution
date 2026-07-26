package br.com.soat.service.dto

import br.com.soat.service.model.Service
import br.com.soat.shared.dto.SupplyRequirementDTO
import java.math.BigDecimal
import java.util.UUID

data class ServiceResponseDTO(
    val id: UUID,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val requiredSupplies: List<SupplyRequirementDTO>,
) {
    companion object {
        fun from(service: Service) = ServiceResponseDTO(
            id = service.id,
            name = service.name,
            description = service.description,
            price = service.price,
            requiredSupplies = service.requiredSupplies.map {
                SupplyRequirementDTO(it.supplyId, it.quantity)
            },
        )
    }
}
