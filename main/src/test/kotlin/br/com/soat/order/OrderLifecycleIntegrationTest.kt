package br.com.soat.order

import br.com.soat.EventFixtures
import br.com.soat.IntegrationTest
import br.com.soat.event.model.DomainEventType
import br.com.soat.execution.model.ExecutionStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class OrderLifecycleIntegrationTest : IntegrationTest() {

    private fun enqueuedOrder(mechanicId: UUID = UUID.randomUUID()): UUID {
        val serviceId = http.createService("Troca de oleo", "100.00".toBigDecimal(), adminHeaders())["id"].asText()
        val supplyId = http.createSupply("Filtro de oleo", 5, "30.00".toBigDecimal(), adminHeaders())["id"].asText()
        val orderId = UUID.randomUUID()

        sendToQueue(EventFixtures.orderCreated(http.mapper, orderId))
        waitForExecutionStatus(orderId, ExecutionStatus.AWAITING_DIAGNOSIS)

        assertEquals(
            200,
            http.finishDiagnosis(
                orderId.toString(), listOf(serviceId), listOf(supplyId to 2), mechanicHeaders(mechanicId),
            ).statusCode(),
        )
        waitForPublishedEvent(DomainEventType.DIAGNOSE_FINISHED, orderId.toString())

        sendToQueue(EventFixtures.paymentConfirmed(http.mapper, orderId, "pay-x", "160.00".toBigDecimal()))
        waitForExecutionStatus(orderId, ExecutionStatus.ENQUEUED)
        return orderId
    }

    private fun startedOrder(mechanicId: UUID = UUID.randomUUID()): UUID {
        val orderId = enqueuedOrder(mechanicId)
        val started = http.startOrder(orderId.toString(), mechanicHeaders())
        assertEquals(200, started.statusCode())
        assertEquals("IN_PROGRESS", http.mapper.readTree(started.body())["status"].asText())
        waitForPublishedEvent(DomainEventType.EXECUTION_STARTED, orderId.toString())
        return orderId
    }

    @Test
    fun `full lifecycle from diagnosis to finish`() {
        val orderId = startedOrder()

        val finished = http.finish(orderId.toString(), mechanicHeaders())
        assertEquals(200, finished.statusCode())
        assertEquals("COMPLETED", http.mapper.readTree(finished.body())["status"].asText())
        waitForPublishedEvent(DomainEventType.EXECUTION_FINISHED, orderId.toString())
    }

    @Test
    fun `fail emits ExecutionFailed carrying paymentId`() {
        val orderId = startedOrder()

        val response = http.fail(orderId.toString(), "peca com defeito", mechanicHeaders())
        assertEquals(200, response.statusCode())
        assertEquals("FAILED", http.mapper.readTree(response.body())["status"].asText())

        val payload = waitForPublishedEvent(DomainEventType.EXECUTION_FAILED, orderId.toString())
        assertEquals("pay-x", payload["paymentId"].asText())
        assertEquals("peca com defeito", payload["reason"].asText())
    }

    @Test
    fun `finish-diagnosis records the diagnosing mechanic taken from the JWT`() {
        val mechanicId = UUID.randomUUID()
        val orderId = enqueuedOrder(mechanicId)

        val response = http.getOrder(orderId.toString(), mechanicHeaders())
        assertEquals(200, response.statusCode())
        val diagnosedBy = http.mapper.readTree(response.body())["diagnosedBy"]
        assertEquals(mechanicId.toString(), diagnosedBy["id"].asText())
        assertEquals("12345678909", diagnosedBy["document"].asText())
    }

    @Test
    fun `an enqueued order shows up in the ENQUEUED queue`() {
        val orderId = enqueuedOrder()
        val response = http.listOrders("ENQUEUED", mechanicHeaders())
        assertEquals(200, response.statusCode())
        val ids = http.mapper.readTree(response.body()).map { it["orderId"].asText() }
        assertTrue(ids.contains(orderId.toString()))
    }

    @Test
    fun `an order awaiting diagnosis shows up in the default listing`() {
        val orderId = UUID.randomUUID()
        sendToQueue(EventFixtures.orderCreated(http.mapper, orderId))
        waitForExecutionStatus(orderId, ExecutionStatus.AWAITING_DIAGNOSIS)

        val response = http.get("/v1/orders", mechanicHeaders())
        assertEquals(200, response.statusCode())
        val ids = http.mapper.readTree(response.body()).map { it["orderId"].asText() }
        assertTrue(ids.contains(orderId.toString()))
    }

    @Test
    fun `starting an order still awaiting diagnosis returns 409`() {
        val orderId = UUID.randomUUID()
        sendToQueue(EventFixtures.orderCreated(http.mapper, orderId))
        waitForExecutionStatus(orderId, ExecutionStatus.AWAITING_DIAGNOSIS)

        assertEquals(409, http.startOrder(orderId.toString(), mechanicHeaders()).statusCode())
    }

    @Test
    fun `finishing an enqueued order returns 409`() {
        val orderId = enqueuedOrder()

        assertEquals(409, http.finish(orderId.toString(), mechanicHeaders()).statusCode())
    }

    @Test
    fun `unknown order returns 404`() {
        assertEquals(404, http.getOrder(UUID.randomUUID().toString(), mechanicHeaders()).statusCode())
    }
}
