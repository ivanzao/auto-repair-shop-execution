package br.com.soat.reservation.model

import java.util.UUID

data class ReservationLine(
    val supplyId: UUID,
    val quantity: Int,
)
