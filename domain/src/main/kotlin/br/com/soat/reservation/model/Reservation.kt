package br.com.soat.reservation.model

import java.time.Instant
import java.util.UUID

data class Reservation(
    val id: UUID = UUID.randomUUID(),
    val orderId: UUID,
    val status: ReservationStatus = ReservationStatus.ACTIVE,
    val lines: List<ReservationLine>,
    val expiresAt: Instant,
    val createdAt: Instant = Instant.now(),
) {
    fun release() = copy(status = ReservationStatus.RELEASED)
    fun expire() = copy(status = ReservationStatus.EXPIRED)
}
