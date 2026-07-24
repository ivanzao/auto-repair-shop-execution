package br.com.soat.reservation.repository

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import br.com.soat.reservation.model.Reservation
import java.time.Instant
import java.util.UUID

interface ReservationRepository {
    fun findById(id: UUID): Reservation?

    fun findActiveExpiredBefore(now: Instant, limit: Int): List<Reservation>

    fun putItem(reservation: Reservation): Map<String, AttributeValue>
    fun save(reservation: Reservation)
}
