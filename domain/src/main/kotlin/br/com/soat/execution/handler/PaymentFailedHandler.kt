package br.com.soat.execution.handler

import br.com.soat.event.DomainEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.DomainEventType
import br.com.soat.execution.ReleaseReservationUseCase
import java.util.UUID

class PaymentFailedHandler(
    private val releaseReservation: ReleaseReservationUseCase,
) : DomainEventHandler {

    override val eventType = DomainEventType.PAYMENT_FAILED

    override fun handle(env: EventEnvelope) {
        val reservationId = UUID.fromString(env.payload["reservationId"].asText())
        releaseReservation.release(reservationId)
    }
}
