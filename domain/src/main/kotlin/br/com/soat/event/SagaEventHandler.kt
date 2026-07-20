package br.com.soat.event

import br.com.soat.event.model.EventEnvelope

/**
 * Handler de um tipo de evento consumido da fila de saga. `eventType` casa com `envelope.eventType`.
 */
interface SagaEventHandler {
    val eventType: String
    fun handle(env: EventEnvelope)
}
