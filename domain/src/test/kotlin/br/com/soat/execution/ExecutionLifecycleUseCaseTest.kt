package br.com.soat.execution

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.exception.ExecutionNotFoundException
import br.com.soat.execution.exception.InvalidExecutionTransitionException
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
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ExecutionLifecycleUseCaseTest {

    private val mapper = jacksonObjectMapper()
    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val writer = mockk<TransactionalWriter>(relaxed = true)
    private val useCase = ExecutionLifecycleUseCase(executionRepository, outbox, writer, mapper)

    private val orderId = UUID.randomUUID()

    private fun execution(status: ExecutionStatus, paymentId: String? = null) =
        Execution(orderId = orderId, status = status, paymentId = paymentId, orderSnapshot = MissingNode.getInstance())

    @Test
    fun `finish on DIAGNOSED emits ExecutionFinished and completes`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.DIAGNOSED)
        val execSlot = slot<Execution>()
        every { executionRepository.putItem(capture(execSlot)) } returns emptyMap()
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        val result = useCase.finish(orderId)

        assertEquals(ExecutionStatus.COMPLETED, result.status)
        assertEquals(ExecutionStatus.COMPLETED, execSlot.captured.status)
        assertEquals(SagaEventType.EXECUTION_FINISHED, envSlot.captured.eventType)
        assertEquals(orderId.toString(), envSlot.captured.payload["orderId"].asText())
    }

    @Test
    fun `finishDiagnosis emits DiagnoseFinished`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.IN_PROGRESS)
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        val result = useCase.finishDiagnosis(orderId)

        assertEquals(ExecutionStatus.DIAGNOSED, result.status)
        assertEquals(SagaEventType.DIAGNOSE_FINISHED, envSlot.captured.eventType)
    }

    @Test
    fun `fail emits ExecutionFailed carrying paymentId and reason`() {
        every { executionRepository.findByOrderId(orderId) } returns
            execution(ExecutionStatus.IN_PROGRESS, paymentId = "pay-9")
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        val result = useCase.fail(orderId, "peça quebrou")

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(SagaEventType.EXECUTION_FAILED, envSlot.captured.eventType)
        assertEquals("pay-9", envSlot.captured.payload["paymentId"].asText())
        assertEquals("peça quebrou", envSlot.captured.payload["reason"].asText())
    }

    @Test
    fun `finish on RESERVED throws invalid transition (409)`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.RESERVED)
        assertThrows(InvalidExecutionTransitionException::class.java) { useCase.finish(orderId) }
        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
    }

    @Test
    fun `get throws when execution missing (404)`() {
        every { executionRepository.findByOrderId(orderId) } returns null
        assertThrows(ExecutionNotFoundException::class.java) { useCase.get(orderId) }
    }
}
