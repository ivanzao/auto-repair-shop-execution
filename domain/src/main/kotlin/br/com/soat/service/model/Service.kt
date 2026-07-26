package br.com.soat.service.model

import br.com.soat.shared.model.SupplyRequirement
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

data class Service(
    val id: UUID = UUID.randomUUID(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val modifiedAt: LocalDateTime = LocalDateTime.now(),
    val version: Int = 0,

    val requiredSupplies: List<SupplyRequirement> = emptyList(),
    val name: String,
    val description: String?,
    val price: BigDecimal,
)
