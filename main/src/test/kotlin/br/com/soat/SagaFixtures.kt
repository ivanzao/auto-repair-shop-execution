package br.com.soat

import br.com.soat.event.model.SagaEventType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.util.UUID

/** Constrói envelopes crus de saga como os produzidos por order/billing. */
object SagaFixtures {

    fun orderCreated(
        mapper: ObjectMapper,
        orderId: UUID,
        supplyId: UUID,
        quantity: Int,
        customerName: String = "Alice",
        customerEmail: String = "alice@example.com",
        serviceName: String = "Troca de óleo",
        servicePrice: BigDecimal = "149.90".toBigDecimal(),
    ): JsonNode {
        val payload = mapper.createObjectNode()
        payload.put("orderId", orderId.toString())
        payload.putObject("customer").apply {
            put("id", UUID.randomUUID().toString())
            put("name", customerName)
            put("email", customerEmail)
        }
        payload.putObject("vehicle").apply {
            put("plate", "ABC1D23")
            put("model", "Fusca")
        }
        payload.putArray("services").addObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", serviceName)
            put("price", servicePrice)
        }
        payload.putArray("supplies").addObject().apply {
            put("id", supplyId.toString())
            put("quantity", quantity)
        }
        return envelope(mapper, SagaEventType.ORDER_CREATED, payload)
    }

    fun paymentConfirmed(mapper: ObjectMapper, orderId: UUID, paymentId: String, amount: BigDecimal): JsonNode {
        val payload = mapper.createObjectNode()
            .put("orderId", orderId.toString())
            .put("paymentId", paymentId)
            .put("amount", amount)
        return envelope(mapper, SagaEventType.PAYMENT_CONFIRMED, payload)
    }

    fun quoteRejected(mapper: ObjectMapper, orderId: UUID, reservationId: UUID): JsonNode {
        val payload = mapper.createObjectNode()
            .put("orderId", orderId.toString())
            .put("reservationId", reservationId.toString())
        return envelope(mapper, SagaEventType.QUOTE_REJECTED, payload)
    }

    fun paymentFailed(mapper: ObjectMapper, orderId: UUID, reservationId: UUID, reason: String = "recusado"): JsonNode {
        val payload = mapper.createObjectNode()
            .put("orderId", orderId.toString())
            .put("reservationId", reservationId.toString())
            .put("reason", reason)
        return envelope(mapper, SagaEventType.PAYMENT_FAILED, payload)
    }

    private fun envelope(mapper: ObjectMapper, eventType: String, payload: JsonNode): JsonNode {
        return mapper.createObjectNode().apply {
            put("eventId", UUID.randomUUID().toString())
            put("eventType", eventType)
            put("eventVersion", 1)
            put("occurredAt", java.time.Instant.now().toString())
            set<JsonNode>("payload", payload)
        }
    }
}
