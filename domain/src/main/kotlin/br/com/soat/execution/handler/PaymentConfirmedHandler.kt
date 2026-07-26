package br.com.soat.execution.handler

import br.com.soat.event.DomainEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.DomainEventType
import br.com.soat.execution.ConfirmPaymentUseCase
import java.util.UUID

class PaymentConfirmedHandler(
    private val confirmPayment: ConfirmPaymentUseCase,
) : DomainEventHandler {

    override val eventType = DomainEventType.PAYMENT_CONFIRMED

    override fun handle(env: EventEnvelope) {
        val orderId = UUID.fromString(env.payload["orderId"].asText())
        val paymentId = env.payload["paymentId"].asText()
        confirmPayment.confirm(orderId, paymentId)
    }
}
