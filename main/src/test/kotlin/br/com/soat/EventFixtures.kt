package br.com.soat

import br.com.soat.event.model.DomainEventType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.util.UUID

private const val ORDER_CREATED = """
{
  "orderId": "11111111-1111-1111-1111-111111111111",
  "customer": {
    "id": "22222222-2222-2222-2222-222222222222",
    "name": "Maria Silva",
    "email": "maria@exemplo.com"
  },
  "vehicle": {
    "plate": "ABC1234",
    "model": "Gol 1.6"
  }
}
"""

object EventFixtures {

    fun orderCreated(mapper: ObjectMapper, orderId: UUID): JsonNode {
        val payload = mapper.readTree(ORDER_CREATED) as ObjectNode
        payload.put("orderId", orderId.toString())
        return envelope(mapper, DomainEventType.ORDER_CREATED, payload)
    }

    fun paymentConfirmed(mapper: ObjectMapper, orderId: UUID, paymentId: String, amount: BigDecimal): JsonNode {
        val payload = mapper.createObjectNode()
            .put("orderId", orderId.toString())
            .put("paymentId", paymentId)
            .put("amount", amount)
        return envelope(mapper, DomainEventType.PAYMENT_CONFIRMED, payload)
    }

    fun quoteRejected(mapper: ObjectMapper, orderId: UUID, reservationId: UUID): JsonNode {
        val payload = mapper.createObjectNode()
            .put("orderId", orderId.toString())
            .put("reservationId", reservationId.toString())
        return envelope(mapper, DomainEventType.QUOTE_REJECTED, payload)
    }

    fun paymentFailed(mapper: ObjectMapper, orderId: UUID, reservationId: UUID, reason: String = "recusado"): JsonNode {
        val payload = mapper.createObjectNode()
            .put("orderId", orderId.toString())
            .put("reservationId", reservationId.toString())
            .put("reason", reason)
        return envelope(mapper, DomainEventType.PAYMENT_FAILED, payload)
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
