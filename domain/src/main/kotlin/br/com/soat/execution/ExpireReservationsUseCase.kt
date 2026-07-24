package br.com.soat.execution

import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.reservation.repository.ReservationRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import org.slf4j.LoggerFactory

class ExpireReservationsUseCase(
    private val reservationRepository: ReservationRepository,
    private val release: ReleaseReservationUseCase,
    private val mapper: ObjectMapper,
    private val now: () -> Instant = { Instant.now() },
) {
    private val logger = LoggerFactory.getLogger(ExpireReservationsUseCase::class.java)

    fun run() {
        val expired = reservationRepository.findActiveExpiredBefore(now(), BATCH)
        if (expired.isEmpty()) return
        logger.info("Expiring {} reservation(s)", expired.size)
        expired.forEach { reservation ->
            release.release(reservation.id) { execution ->
                EventEnvelope(
                    eventType = SagaEventType.RESERVATION_EXPIRED,
                    payload = mapper.createObjectNode()
                        .put("orderId", execution.orderId.toString())
                        .put("reservationId", reservation.id.toString()),
                )
            }
        }
    }

    companion object {
        private const val BATCH = 25
    }
}
