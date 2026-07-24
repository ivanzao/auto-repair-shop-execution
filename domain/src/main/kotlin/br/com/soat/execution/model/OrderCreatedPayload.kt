package br.com.soat.execution.model

import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal
import java.util.UUID

data class OrderCreatedPayload(
    val orderId: UUID,
    val customer: Customer,
    val services: List<ServiceLine>,
    val supplies: List<SupplyLine>,
) {
    data class Customer(val name: String, val email: String)
    data class ServiceLine(val name: String, val price: BigDecimal)
    data class SupplyLine(val id: UUID, val quantity: Int)

    companion object {
        fun from(payload: JsonNode): OrderCreatedPayload {
            val customer = payload["customer"]
            return OrderCreatedPayload(
                orderId = UUID.fromString(payload["orderId"].asText()),
                customer = Customer(customer["name"].asText(), customer["email"].asText()),
                services = payload["services"].map {
                    ServiceLine(it["name"].asText(), it["price"].decimalValue())
                },
                supplies = payload["supplies"].map {
                    SupplyLine(UUID.fromString(it["id"].asText()), it["quantity"].asInt())
                },
            )
        }
    }
}
