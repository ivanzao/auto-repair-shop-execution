package br.com.soat.execution.model

import java.util.UUID

data class MissingSupply(
    val supplyId: UUID,
    val name: String,
    val requested: Int,
    val available: Int,
)
