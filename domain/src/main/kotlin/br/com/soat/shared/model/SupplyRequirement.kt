package br.com.soat.shared.model

import java.util.UUID

data class SupplyRequirement(
    val supplyId: UUID,
    val quantity: Int,
)