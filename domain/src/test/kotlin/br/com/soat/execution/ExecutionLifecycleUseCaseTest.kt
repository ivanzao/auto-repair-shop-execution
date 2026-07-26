package br.com.soat.execution

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.DomainEventType
import br.com.soat.event.model.EventEnvelope
import br.com.soat.execution.exception.ExecutionNotFoundException
import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.RecordingMetrics
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
    private val metrics = RecordingMetrics()
    private val useCase = ExecutionLifecycleUseCase(executionRepository, outbox, writer, mapper, metrics)

    private val orderId = UUID.randomUUID()

    private fun execution(status: ExecutionStatus, paymentId: String? = null) =
        Execution(orderId = orderId, status = status, paymentId = paymentId, orderSnapshot = MissingNode.getInstance())

    @Test
    fun `start on ENQUEUED emits ExecutionStarted and moves to IN_PROGRESS`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.ENQUEUED)
        val execSlot = slot<Execution>()
        every { executionRepository.putItem(capture(execSlot)) } returns emptyMap()
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        val result = useCase.start(orderId)

        assertEquals(ExecutionStatus.IN_PROGRESS, result.status)
        assertEquals(ExecutionStatus.IN_PROGRESS, execSlot.captured.status)
        assertEquals(DomainEventType.EXECUTION_STARTED, envSlot.captured.eventType)
        assertEquals(orderId.toString(), envSlot.captured.payload["orderId"].asText())
        assertEquals(listOf(ExecutionStatus.IN_PROGRESS.name), metrics.statuses)
    }

    @Test
    fun `finish on IN_PROGRESS emits ExecutionFinished and completes`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.IN_PROGRESS)
        val execSlot = slot<Execution>()
        every { executionRepository.putItem(capture(execSlot)) } returns emptyMap()
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        val result = useCase.finish(orderId)

        assertEquals(ExecutionStatus.COMPLETED, result.status)
        assertEquals(ExecutionStatus.COMPLETED, execSlot.captured.status)
        assertEquals(DomainEventType.EXECUTION_FINISHED, envSlot.captured.eventType)
        assertEquals(orderId.toString(), envSlot.captured.payload["orderId"].asText())
        assertEquals(listOf(ExecutionStatus.COMPLETED.name), metrics.statuses)
    }

    @Test
    fun `fail emits ExecutionFailed carrying paymentId and reason`() {
        every { executionRepository.findByOrderId(orderId) } returns
            execution(ExecutionStatus.IN_PROGRESS, paymentId = "pay-9")
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        val result = useCase.fail(orderId, "peca quebrou")

        assertEquals(ExecutionStatus.FAILED, result.status)
        assertEquals(DomainEventType.EXECUTION_FAILED, envSlot.captured.eventType)
        assertEquals("pay-9", envSlot.captured.payload["paymentId"].asText())
        assertEquals("peca quebrou", envSlot.captured.payload["reason"].asText())
        assertEquals(listOf(ExecutionStatus.FAILED.name), metrics.statuses)
    }

    @Test
    fun `start on RESERVED throws invalid transition (409)`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.RESERVED)
        assertThrows(InvalidExecutionTransitionException::class.java) { useCase.start(orderId) }
        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
        assertEquals(emptyList<String>(), metrics.statuses)
    }

    @Test
    fun `finish on ENQUEUED throws invalid transition (409)`() {
        every { executionRepository.findByOrderId(orderId) } returns execution(ExecutionStatus.ENQUEUED)
        assertThrows(InvalidExecutionTransitionException::class.java) { useCase.finish(orderId) }
        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
    }

    @Test
    fun `get throws when execution missing (404)`() {
        every { executionRepository.findByOrderId(orderId) } returns null
        assertThrows(ExecutionNotFoundException::class.java) { useCase.get(orderId) }
    }
}
