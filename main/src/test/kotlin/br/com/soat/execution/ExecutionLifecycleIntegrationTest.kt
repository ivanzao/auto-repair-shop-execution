package br.com.soat.execution

import br.com.soat.IntegrationTest
import br.com.soat.SagaFixtures
import br.com.soat.event.model.SagaEventType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ExecutionLifecycleIntegrationTest : IntegrationTest() {

    private fun startedExecution(): UUID {
        val created = http.createSupply("Bateria", 5, "250.00".toBigDecimal(), adminHeaders())
        val supplyId = UUID.fromString(created["id"].asText())
        val orderId = UUID.randomUUID()
        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 1))
        waitForPublishedEvent(SagaEventType.SUPPLIES_RESERVED, orderId.toString())
        sendToQueue(SagaFixtures.paymentConfirmed(http.mapper, orderId, "pay-x", "399.90".toBigDecimal()))
        waitForPublishedEvent(SagaEventType.EXECUTION_STARTED, orderId.toString())
        return orderId
    }

    @Test
    fun `full lifecycle finish-diagnosis then finish`() {
        val orderId = startedExecution()

        assertEquals(200, http.finishDiagnosis(orderId.toString(), mechanicHeaders()).statusCode())
        waitForPublishedEvent(SagaEventType.DIAGNOSE_FINISHED, orderId.toString())

        val finished = http.finish(orderId.toString(), mechanicHeaders())
        assertEquals(200, finished.statusCode())
        assertEquals("COMPLETED", http.mapper.readTree(finished.body())["status"].asText())
        waitForPublishedEvent(SagaEventType.EXECUTION_FINISHED, orderId.toString())
    }

    @Test
    fun `fail emits ExecutionFailed carrying paymentId`() {
        val orderId = startedExecution()

        val response = http.fail(orderId.toString(), "peça com defeito", mechanicHeaders())
        assertEquals(200, response.statusCode())
        assertEquals("FAILED", http.mapper.readTree(response.body())["status"].asText())

        val payload = waitForPublishedEvent(SagaEventType.EXECUTION_FAILED, orderId.toString())
        assertEquals("pay-x", payload["paymentId"].asText())
        assertEquals("peça com defeito", payload["reason"].asText())
    }

    @Test
    fun `queued execution appears in mechanic queue`() {
        val orderId = startedExecution()
        val response = http.listExecutions("IN_PROGRESS", mechanicHeaders())
        assertEquals(200, response.statusCode())
        val ids = http.mapper.readTree(response.body()).map { it["orderId"].asText() }
        assertTrue(ids.contains(orderId.toString()))
    }

    @Test
    fun `finishing a reserved execution returns 409`() {
        val created = http.createSupply("Filtro ar", 5, "20.00".toBigDecimal(), adminHeaders())
        val supplyId = UUID.fromString(created["id"].asText())
        val orderId = UUID.randomUUID()
        sendToQueue(SagaFixtures.orderCreated(http.mapper, orderId, supplyId, quantity = 1))
        waitForPublishedEvent(SagaEventType.SUPPLIES_RESERVED, orderId.toString())

        assertEquals(409, http.finish(orderId.toString(), mechanicHeaders()).statusCode())
    }

    @Test
    fun `unknown execution returns 404`() {
        assertEquals(404, http.getExecution(UUID.randomUUID().toString(), mechanicHeaders()).statusCode())
    }
}
