package br.com.soat.saga

import br.com.soat.IntegrationTest
import br.com.soat.SagaFixtures
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.reservation.model.ReservationStatus
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.supply.repository.SupplyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class SagaFlowIntegrationTest : IntegrationTest() {

    private fun seedSupply(name: String, stock: Int, price: String): UUID {
        val created = http.createSupply(name, stock, price.toBigDecimal(), adminHeaders())
        return UUID.fromString(created["id"].asText())
    }

    private fun stockOf(supplyId: UUID): Int = get<SupplyRepository>().findById(supplyId)!!.quantityInStock

    private fun waitForStock(supplyId: UUID, expected: Int, timeoutSeconds: Long = 20) {
        val deadline = Instant.now().plusSeconds(timeoutSeconds)
        while (Instant.now().isBefore(deadline)) {
            if (stockOf(supplyId) == expected) return
            Thread.sleep(300)
        }
        assertEquals(expected, stockOf(supplyId))
    }

    @Test
    fun `happy path reserves supplies and emits SuppliesReserved with priced quote`() {
        val supplyId = seedSupply("Filtro", stock = 5, price = "30.00")
        val orderId = UUID.randomUUID()

        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 2))

        val payload = waitForPublishedEvent(SagaEventType.SUPPLIES_RESERVED, orderId.toString())
        UUID.fromString(payload["reservationId"].asText())
        assertEquals("Alice", payload["customer"]["name"].asText())
        assertEquals("Filtro", payload["supplies"][0]["name"].asText())
        assertEquals(2, payload["supplies"][0]["quantity"].asInt())
        assertEquals(0, "30.00".toBigDecimal().compareTo(payload["supplies"][0]["unitPrice"].decimalValue()))
        assertEquals(0, "209.90".toBigDecimal().compareTo(payload["totalAmount"].decimalValue()))
        waitForStock(supplyId, 3)
    }

    @Test
    fun `insufficient stock emits PartsUnavailable and leaves stock untouched`() {
        val supplyId = seedSupply("Correia", stock = 1, price = "10.00")
        val orderId = UUID.randomUUID()

        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 2))

        val payload = waitForPublishedEvent(SagaEventType.PARTS_UNAVAILABLE, orderId.toString())
        assertEquals(orderId.toString(), payload["orderId"].asText())
        assertTrue(payload["missingSupplies"].size() >= 1)
        assertEquals(1, stockOf(supplyId))
    }

    @Test
    fun `duplicate OrderCreated reserves and decrements only once`() {
        val supplyId = seedSupply("Vela", stock = 5, price = "5.00")
        val orderId = UUID.randomUUID()

        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 2))
        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 2))

        waitForPublishedEvent(SagaEventType.SUPPLIES_RESERVED, orderId.toString())
        waitForStock(supplyId, 3)
        Thread.sleep(2000)
        assertEquals(3, stockOf(supplyId))
    }

    @Test
    fun `PaymentConfirmed starts execution and emits ExecutionStarted`() {
        val supplyId = seedSupply("Pastilha", stock = 4, price = "40.00")
        val orderId = UUID.randomUUID()
        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 1))
        waitForPublishedEvent(SagaEventType.SUPPLIES_RESERVED, orderId.toString())

        sendToQueue(SagaFixtures.paymentConfirmed(http.mapper, orderId, "pay-1", "189.90".toBigDecimal()))

        waitForPublishedEvent(SagaEventType.EXECUTION_STARTED, orderId.toString())
        val execution = get<ExecutionRepository>().findByOrderId(orderId)!!
        assertEquals(ExecutionStatus.IN_PROGRESS, execution.status)
        assertEquals("pay-1", execution.paymentId)
    }

    @Test
    fun `QuoteRejected releases reservation and restores stock`() {
        val supplyId = seedSupply("Amortecedor", stock = 6, price = "60.00")
        val orderId = UUID.randomUUID()
        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 2))
        val reserved = waitForPublishedEvent(SagaEventType.SUPPLIES_RESERVED, orderId.toString())
        val reservationId = UUID.fromString(reserved["reservationId"].asText())
        waitForStock(supplyId, 4)

        sendToQueue(SagaFixtures.quoteRejected(http.mapper, orderId, reservationId))

        waitForStock(supplyId, 6)
        assertEquals(ReservationStatus.RELEASED, get<ReservationRepository>().findById(reservationId)!!.status)
        assertEquals(ExecutionStatus.CANCELED, get<ExecutionRepository>().findByOrderId(orderId)!!.status)
    }

    @Test
    fun `PaymentFailed releases reservation and restores stock`() {
        val supplyId = seedSupply("Radiador", stock = 3, price = "300.00")
        val orderId = UUID.randomUUID()
        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 1))
        val reserved = waitForPublishedEvent(SagaEventType.SUPPLIES_RESERVED, orderId.toString())
        val reservationId = UUID.fromString(reserved["reservationId"].asText())
        waitForStock(supplyId, 2)

        sendToQueue(SagaFixtures.paymentFailed(http.mapper, orderId, reservationId))

        waitForStock(supplyId, 3)
        assertEquals(ReservationStatus.RELEASED, get<ReservationRepository>().findById(reservationId)!!.status)
    }
}
