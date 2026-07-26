package br.com.soat.service.dto

import br.com.soat.service.model.request.CreateServiceRequest
import br.com.soat.shared.dto.SupplyRequirementDTO
import java.math.BigDecimal

data class CreateServiceRequestDTO(
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val requiredSupplies: List<SupplyRequirementDTO> = emptyList(),
) {

    fun toModel() = CreateServiceRequest(
        name = name,
        description = description,
        price = price,
        requiredSupplies = requiredSupplies.map { it.toModel() },
    )
}
