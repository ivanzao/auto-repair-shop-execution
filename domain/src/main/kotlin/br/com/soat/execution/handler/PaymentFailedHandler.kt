package br.com.soat.execution.handler

import br.com.soat.event.SagaEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.ReleaseReservationUseCase
import java.util.UUID

class PaymentFailedHandler(
    private val releaseReservation: ReleaseReservationUseCase,
) : SagaEventHandler {

    override val eventType = SagaEventType.PAYMENT_FAILED

    override fun handle(env: EventEnvelope) {
        val reservationId = UUID.fromString(env.payload["reservationId"].asText())
        releaseReservation.release(reservationId)
    }
}
