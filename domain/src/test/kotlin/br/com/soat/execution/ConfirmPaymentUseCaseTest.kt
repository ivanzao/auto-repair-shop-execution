package br.com.soat.execution

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import com.fasterxml.jackson.databind.node.MissingNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ConfirmPaymentUseCaseTest {

    private val mapper = jacksonObjectMapper()
    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val writer = mockk<TransactionalWriter>(relaxed = true)
    private val useCase = ConfirmPaymentUseCase(executionRepository, outbox, writer, mapper)

    private val orderId = UUID.randomUUID()

    private fun execution(status: ExecutionStatus) =
        Execution(orderId = orderId, status = status, orderSnapshot = MissingNode.getInstance())

    @Test
    fun `confirms payment and emits ExecutionStarted`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.RESERVED)
        val putSlot = slot<Execution>()
        every { executionRepository.putItem(capture(putSlot)) } returns emptyMap()
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        useCase.confirm(orderId, "pay-123")

        assertEquals(ExecutionStatus.IN_PROGRESS, putSlot.captured.status)
        assertEquals("pay-123", putSlot.captured.paymentId)
        assertEquals(SagaEventType.EXECUTION_STARTED, envSlot.captured.eventType)
        assertEquals(orderId.toString(), envSlot.captured.payload["orderId"].asText())
        verify { writer.writeAll(any(), any(), any()) }
    }

    @Test
    fun `no-op when execution already started`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.IN_PROGRESS)

        useCase.confirm(orderId, "pay-123")

        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
    }

    @Test
    fun `no-op when execution unknown`() {
        every { executionRepository.findByOrderId(orderId) } returns null

        useCase.confirm(orderId, "pay-123")

        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
    }
}
