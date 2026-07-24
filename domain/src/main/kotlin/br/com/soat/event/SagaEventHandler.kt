package br.com.soat.event

import br.com.soat.event.model.EventEnvelope

interface SagaEventHandler {
    val eventType: String
    fun handle(env: EventEnvelope)
}
