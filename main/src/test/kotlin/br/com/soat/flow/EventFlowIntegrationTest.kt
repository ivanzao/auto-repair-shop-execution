package br.com.soat.flow

import br.com.soat.EventFixtures
import br.com.soat.IntegrationTest
import br.com.soat.event.model.DomainEventType
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.reservation.model.ReservationStatus
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.supply.repository.SupplyRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class EventFlowIntegrationTest : IntegrationTest() {

    private fun seedSupply(name: String, stock: Int, price: String): String =
        http.createSupply(name, stock, price.toBigDecimal(), adminHeaders())["id"].asText()

    private fun seedService(name: String, price: String): String =
        http.createService(name, price.toBigDecimal(), adminHeaders())["id"].asText()

    private fun stockOf(supplyId: String): Int =
        get<SupplyRepository>().findById(UUID.fromString(supplyId))!!.quantityInStock

    private fun waitForStock(supplyId: String, expected: Int, timeoutSeconds: Long = 20) {
        val deadline = Instant.now().plusSeconds(timeoutSeconds)
        while (Instant.now().isBefore(deadline)) {
            if (stockOf(supplyId) == expected) return
            Thread.sleep(300)
        }
        assertEquals(expected, stockOf(supplyId))
    }

    private fun enqueueOrder(orderId: UUID) {
        sendToQueue(EventFixtures.orderCreated(http.mapper, orderId))
        waitForExecutionStatus(orderId, ExecutionStatus.AWAITING_DIAGNOSIS)
    }

    @Test
    fun `OrderCreated enqueues the order awaiting diagnosis without emitting`() {
        val orderId = UUID.randomUUID()

        sendToQueue(EventFixtures.orderCreated(http.mapper, orderId))

        val execution = waitForExecutionStatus(orderId, ExecutionStatus.AWAITING_DIAGNOSIS)
        assertEquals(orderId, execution.orderId)
        assertEquals("Maria Silva", execution.orderSnapshot["customer"]["name"].asText())
        assertEquals("ABC1234", execution.orderSnapshot["vehicle"]["plate"].asText())
    }

    @Test
    fun `finish-diagnosis reserves supplies and emits DiagnoseFinished with the priced quote`() {
        val serviceId = seedService("Troca de oleo", "100.00")
        val supplyId = seedSupply("Filtro de oleo", stock = 5, price = "30.00")
        val orderId = UUID.randomUUID()
        val mechanicId = UUID.randomUUID()
        enqueueOrder(orderId)

        val response = http.finishDiagnosis(
            orderId.toString(), listOf(serviceId), listOf(supplyId to 2), mechanicHeaders(mechanicId),
        )
        assertEquals(200, response.statusCode())
        assertEquals("RESERVED", http.mapper.readTree(response.body())["status"].asText())

        val payload = waitForPublishedEvent(DomainEventType.DIAGNOSE_FINISHED, orderId.toString())
        UUID.fromString(payload["reservationId"].asText())
        assertEquals(mechanicId.toString(), payload["diagnosedBy"]["id"].asText())
        assertEquals("12345678909", payload["diagnosedBy"]["document"].asText())
        assertEquals("Maria Silva", payload["customer"]["name"].asText())
        assertEquals("maria@exemplo.com", payload["customer"]["email"].asText())
        assertEquals(serviceId, payload["services"][0]["id"].asText())
        assertEquals("Troca de oleo", payload["services"][0]["name"].asText())
        assertEquals(0, "100.00".toBigDecimal().compareTo(payload["services"][0]["price"].decimalValue()))
        assertEquals(supplyId, payload["supplies"][0]["id"].asText())
        assertEquals("Filtro de oleo", payload["supplies"][0]["name"].asText())
        assertEquals(2, payload["supplies"][0]["quantity"].asInt())
        assertEquals(0, "30.00".toBigDecimal().compareTo(payload["supplies"][0]["unitPrice"].decimalValue()))
        assertEquals(0, "160.00".toBigDecimal().compareTo(payload["totalAmount"].decimalValue()))
        waitForStock(supplyId, 3)
    }

    @Test
    fun `insufficient stock answers 409, cancels the execution and emits SuppliesUnavailable`() {
        val serviceId = seedService("Alinhamento", "80.00")
        val supplyId = seedSupply("Correia", stock = 1, price = "10.00")
        val orderId = UUID.randomUUID()
        enqueueOrder(orderId)

        val response = http.finishDiagnosis(
            orderId.toString(), listOf(serviceId), listOf(supplyId to 4), mechanicHeaders(),
        )
        assertEquals(409, response.statusCode())
        val body = http.mapper.readTree(response.body())
        assertEquals("SUPPLIES_UNAVAILABLE", body["code"].asText())
        assertEquals("Correia", body["missingSupplies"][0]["name"].asText())
        assertEquals(4, body["missingSupplies"][0]["requested"].asInt())
        assertEquals(1, body["missingSupplies"][0]["available"].asInt())

        val payload = waitForPublishedEvent(DomainEventType.SUPPLIES_UNAVAILABLE, orderId.toString())
        assertEquals(supplyId, payload["missingSupplies"][0]["supplyId"].asText())
        assertEquals("Correia", payload["missingSupplies"][0]["name"].asText())
        assertEquals(4, payload["missingSupplies"][0]["requested"].asInt())
        assertEquals(1, payload["missingSupplies"][0]["available"].asInt())
        assertEquals(1, stockOf(supplyId))
        assertEquals(ExecutionStatus.CANCELED, get<ExecutionRepository>().findByOrderId(orderId)!!.status)
    }

    @Test
    fun `unknown service in the diagnosis returns 404`() {
        val supplyId = seedSupply("Vela", stock = 5, price = "5.00")
        val orderId = UUID.randomUUID()
        enqueueOrder(orderId)

        val response = http.finishDiagnosis(
            orderId.toString(), listOf(UUID.randomUUID().toString()), listOf(supplyId to 1), mechanicHeaders(),
        )
        assertEquals(404, response.statusCode())
    }

    @Test
    fun `duplicate OrderCreated enqueues the order only once`() {
        val orderId = UUID.randomUUID()

        sendToQueue(EventFixtures.orderCreated(http.mapper, orderId))
        sendToQueue(EventFixtures.orderCreated(http.mapper, orderId))

        val first = waitForExecutionStatus(orderId, ExecutionStatus.AWAITING_DIAGNOSIS)
        Thread.sleep(2000)
        val second = get<ExecutionRepository>().findByOrderId(orderId)!!
        assertEquals(ExecutionStatus.AWAITING_DIAGNOSIS, second.status)
        assertEquals(first.createdAt, second.createdAt)
    }

    @Test
    fun `second finish-diagnosis on the same order returns 409`() {
        val serviceId = seedService("Revisao", "500.00")
        val supplyId = seedSupply("Oleo", stock = 10, price = "45.00")
        val orderId = UUID.randomUUID()
        enqueueOrder(orderId)

        assertEquals(
            200,
            http.finishDiagnosis(orderId.toString(), listOf(serviceId), listOf(supplyId to 1), mechanicHeaders())
                .statusCode(),
        )
        assertEquals(
            409,
            http.finishDiagnosis(orderId.toString(), listOf(serviceId), listOf(supplyId to 1), mechanicHeaders())
                .statusCode(),
        )
    }

    @Test
    fun `PaymentConfirmed stops at ENQUEUED without emitting ExecutionStarted`() {
        val serviceId = seedService("Freios", "200.00")
        val supplyId = seedSupply("Pastilha", stock = 4, price = "40.00")
        val orderId = UUID.randomUUID()
        enqueueOrder(orderId)
        http.finishDiagnosis(orderId.toString(), listOf(serviceId), listOf(supplyId to 1), mechanicHeaders())
        waitForPublishedEvent(DomainEventType.DIAGNOSE_FINISHED, orderId.toString())

        sendToQueue(EventFixtures.paymentConfirmed(http.mapper, orderId, "pay-1", "240.00".toBigDecimal()))

        val execution = waitForExecutionStatus(orderId, ExecutionStatus.ENQUEUED)
        assertEquals("pay-1", execution.paymentId)
    }

    @Test
    fun `QuoteRejected releases reservation and restores stock`() {
        val serviceId = seedService("Suspensao", "300.00")
        val supplyId = seedSupply("Amortecedor", stock = 6, price = "60.00")
        val orderId = UUID.randomUUID()
        enqueueOrder(orderId)
        http.finishDiagnosis(orderId.toString(), listOf(serviceId), listOf(supplyId to 2), mechanicHeaders())
        val finished = waitForPublishedEvent(DomainEventType.DIAGNOSE_FINISHED, orderId.toString())
        val reservationId = UUID.fromString(finished["reservationId"].asText())
        waitForStock(supplyId, 4)

        sendToQueue(EventFixtures.quoteRejected(http.mapper, orderId, reservationId))

        waitForStock(supplyId, 6)
        assertEquals(ReservationStatus.RELEASED, get<ReservationRepository>().findById(reservationId)!!.status)
        assertEquals(ExecutionStatus.CANCELED, get<ExecutionRepository>().findByOrderId(orderId)!!.status)
    }

    @Test
    fun `PaymentFailed releases reservation and restores stock`() {
        val serviceId = seedService("Arrefecimento", "150.00")
        val supplyId = seedSupply("Radiador", stock = 3, price = "300.00")
        val orderId = UUID.randomUUID()
        enqueueOrder(orderId)
        http.finishDiagnosis(orderId.toString(), listOf(serviceId), listOf(supplyId to 1), mechanicHeaders())
        val finished = waitForPublishedEvent(DomainEventType.DIAGNOSE_FINISHED, orderId.toString())
        val reservationId = UUID.fromString(finished["reservationId"].asText())
        waitForStock(supplyId, 2)

        sendToQueue(EventFixtures.paymentFailed(http.mapper, orderId, reservationId))

        waitForStock(supplyId, 3)
        assertEquals(ReservationStatus.RELEASED, get<ReservationRepository>().findById(reservationId)!!.status)
    }
}
