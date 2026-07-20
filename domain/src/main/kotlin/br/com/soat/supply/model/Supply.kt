package br.com.soat.supply.model

import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

/**
 * Agregado de estoque do execution. `quantityInStock` é decrementado de forma condicional
 * na reserva de insumos (ver TransactionalWriter) e restaurado nas compensações.
 */
data class Supply(
    val id: UUID = UUID.randomUUID(),
    val createdAt: LocalDateTime = LocalDateTime.now(),
    val modifiedAt: LocalDateTime = LocalDateTime.now(),
    val version: Int = 0,

    val name: String,
    val description: String?,
    val quantityInStock: Int,
    val price: BigDecimal,
)
