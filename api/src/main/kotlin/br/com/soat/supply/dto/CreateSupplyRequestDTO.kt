package br.com.soat.supply.dto

import br.com.soat.supply.model.request.CreateSupplyRequest
import java.math.BigDecimal

data class CreateSupplyRequestDTO(
    val name: String,
    val description: String?,
    val quantityInStock: Int,
    val price: BigDecimal,
) {
    fun toModel() = CreateSupplyRequest(
        name = name,
        description = description,
        quantityInStock = quantityInStock,
        price = price,
    )
}
