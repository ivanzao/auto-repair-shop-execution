package br.com.soat.supply.dto

import br.com.soat.supply.model.Supply
import java.math.BigDecimal
import java.util.UUID

data class SupplyResponseDTO(
    val id: UUID,
    val name: String,
    val description: String?,
    val quantityInStock: Int,
    val price: BigDecimal,
) {
    companion object {
        fun from(supply: Supply) = SupplyResponseDTO(
            id = supply.id,
            name = supply.name,
            description = supply.description,
            quantityInStock = supply.quantityInStock,
            price = supply.price,
        )
    }
}
