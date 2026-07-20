package br.com.soat.execution.handler

import br.com.soat.event.SagaEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.ReserveSuppliesUseCase
import br.com.soat.execution.model.OrderCreatedPayload

class OrderCreatedHandler(
    private val reserveSupplies: ReserveSuppliesUseCase,
) : SagaEventHandler {

    override val eventType = SagaEventType.ORDER_CREATED

    override fun handle(env: EventEnvelope) {
        reserveSupplies.reserve(OrderCreatedPayload.from(env.payload))
    }
}
