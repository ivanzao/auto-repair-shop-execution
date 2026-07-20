package br.com.soat.execution.handler

import br.com.soat.event.SagaEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.ReleaseReservationUseCase
import java.util.UUID

/**
 * QuoteRejected (billing) → libera a reserva. Sem evento de saída: o order já reage ao próprio
 * QuoteRejected; o execution apenas devolve estoque e cancela a Execution.
 */
class QuoteRejectedHandler(
    private val releaseReservation: ReleaseReservationUseCase,
) : SagaEventHandler {

    override val eventType = SagaEventType.QUOTE_REJECTED

    override fun handle(env: EventEnvelope) {
        val reservationId = UUID.fromString(env.payload["reservationId"].asText())
        releaseReservation.release(reservationId)
    }
}
