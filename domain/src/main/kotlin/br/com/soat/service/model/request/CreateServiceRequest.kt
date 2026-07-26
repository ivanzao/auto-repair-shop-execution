package br.com.soat.service.model.request

import br.com.soat.shared.model.SupplyRequirement
import java.math.BigDecimal

data class CreateServiceRequest(
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val requiredSupplies: List<SupplyRequirement> = emptyList(),
)
