package br.com.soat.execution.model

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

data class OrderCreatedPayload(
    val orderId: UUID,
    val customer: Customer,
    val vehicle: Vehicle,
) {
    data class Customer(val id: UUID, val name: String, val email: String)
    data class Vehicle(val plate: String, val model: String)

    companion object {
        fun from(payload: JsonNode): OrderCreatedPayload {
            val customer = payload["customer"]
            val vehicle = payload["vehicle"]
            return OrderCreatedPayload(
                orderId = UUID.fromString(payload["orderId"].asText()),
                customer = Customer(
                    id = UUID.fromString(customer["id"].asText()),
                    name = customer["name"].asText(),
                    email = customer["email"].asText(),
                ),
                vehicle = Vehicle(
                    plate = vehicle["plate"].asText(),
                    model = vehicle["model"].asText(),
                ),
            )
        }
    }
}
