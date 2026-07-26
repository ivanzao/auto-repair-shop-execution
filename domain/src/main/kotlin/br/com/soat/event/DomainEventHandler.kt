package br.com.soat.event

import br.com.soat.event.model.EventEnvelope

interface DomainEventHandler {
    val eventType: String
    fun handle(env: EventEnvelope)
}
