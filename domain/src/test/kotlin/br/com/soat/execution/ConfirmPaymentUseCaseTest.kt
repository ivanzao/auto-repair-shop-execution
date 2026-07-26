package br.com.soat.execution

import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.RecordingMetrics
import com.fasterxml.jackson.databind.node.MissingNode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class ConfirmPaymentUseCaseTest {

    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)
    private val writer = mockk<TransactionalWriter>(relaxed = true)
    private val metrics = RecordingMetrics()
    private val useCase = ConfirmPaymentUseCase(executionRepository, writer, metrics)

    private val orderId = UUID.randomUUID()

    private fun execution(status: ExecutionStatus) =
        Execution(orderId = orderId, status = status, orderSnapshot = MissingNode.getInstance())

    @Test
    fun `confirms payment and stops at ENQUEUED without emitting`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.RESERVED)
        val putSlot = slot<Execution>()
        every { executionRepository.putItem(capture(putSlot)) } returns emptyMap()
        val putsSlot = slot<List<TxPut>>()
        every { writer.writeAll(capture(putsSlot), any(), any()) } returns TxResult.SUCCESS

        useCase.confirm(orderId, "pay-123")

        assertEquals(ExecutionStatus.ENQUEUED, putSlot.captured.status)
        assertEquals("pay-123", putSlot.captured.paymentId)
        assertEquals(1, putsSlot.captured.size)
        assertEquals("#st = :reserved", putsSlot.captured.single().conditionExpression)
        assertEquals(listOf(ExecutionStatus.ENQUEUED.name), metrics.statuses)
    }

    @Test
    fun `no-op when execution already left RESERVED`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.IN_PROGRESS)

        useCase.confirm(orderId, "pay-123")

        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
        assertEquals(emptyList<String>(), metrics.statuses)
    }

    @Test
    fun `no-op when execution unknown`() {
        every { executionRepository.findByOrderId(orderId) } returns null

        useCase.confirm(orderId, "pay-123")

        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
        assertEquals(emptyList<String>(), metrics.statuses)
    }
}
