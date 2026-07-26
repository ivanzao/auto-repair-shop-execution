package br.com.soat.execution.handler

import br.com.soat.event.DomainEventHandler
import br.com.soat.event.model.DomainEventType
import br.com.soat.event.model.EventEnvelope
import br.com.soat.execution.EnqueueForDiagnosisUseCase
import br.com.soat.execution.model.OrderCreatedPayload

class OrderCreatedHandler(
    private val enqueueForDiagnosis: EnqueueForDiagnosisUseCase,
) : DomainEventHandler {

    override val eventType = DomainEventType.ORDER_CREATED

    override fun handle(env: EventEnvelope) {
        enqueueForDiagnosis.enqueue(OrderCreatedPayload.from(env.payload))
    }
}
