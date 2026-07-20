package br.com.soat.reservation.repository

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import br.com.soat.reservation.model.Reservation
import java.time.Instant
import java.util.UUID

interface ReservationRepository {
    fun findById(id: UUID): Reservation?

    /** Reservas ACTIVE cujo expiresAt (gsi1sk) é anterior a `now`. Usado pelo job de expiração. */
    fun findActiveExpiredBefore(now: Instant, limit: Int): List<Reservation>

    /**
     * Monta o item para TransactWriteItems. Esparso: só reserva ACTIVE recebe gsi1pk/gsi1sk;
     * ao desativar (RELEASED/EXPIRED) o item volta sem as chaves do GSI (sai do índice de expiração).
     */
    fun putItem(reservation: Reservation): Map<String, AttributeValue>
    fun save(reservation: Reservation)
}
