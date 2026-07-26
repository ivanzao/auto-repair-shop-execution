package br.com.soat.execution

import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.model.OrderCreatedPayload
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.RecordingMetrics
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

private const val ORDER_CREATED = """
{
  "orderId": "11111111-1111-1111-1111-111111111111",
  "customer": {
    "id": "22222222-2222-2222-2222-222222222222",
    "name": "Maria Silva",
    "email": "maria@exemplo.com"
  },
  "vehicle": {
    "plate": "ABC1234",
    "model": "Gol 1.6"
  }
}
"""

class EnqueueForDiagnosisUseCaseTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)
    private val writer = mockk<TransactionalWriter>()
    private val metrics = RecordingMetrics()
    private val useCase = EnqueueForDiagnosisUseCase(executionRepository, writer, mapper, metrics)

    private val order = OrderCreatedPayload.from(mapper.readTree(ORDER_CREATED))

    @Test
    fun `creates the execution awaiting diagnosis and emits nothing`() {
        every { writer.writeAll(any(), any(), any()) } returns TxResult.SUCCESS
        val execSlot = slot<Execution>()
        every { executionRepository.putItem(capture(execSlot)) } returns emptyMap()
        val putsSlot = slot<List<TxPut>>()
        every { writer.writeAll(capture(putsSlot), any(), any()) } returns TxResult.SUCCESS

        useCase.enqueue(order)

        assertEquals(UUID.fromString("11111111-1111-1111-1111-111111111111"), execSlot.captured.orderId)
        assertEquals(ExecutionStatus.AWAITING_DIAGNOSIS, execSlot.captured.status)
        assertEquals(1, putsSlot.captured.size)
        assertEquals("attribute_not_exists(pk)", putsSlot.captured.single().conditionExpression)
    }

    @Test
    fun `keeps the thin order snapshot for the diagnosis`() {
        every { writer.writeAll(any(), any(), any()) } returns TxResult.SUCCESS
        val execSlot = slot<Execution>()
        every { executionRepository.putItem(capture(execSlot)) } returns emptyMap()

        useCase.enqueue(order)

        val snapshot = execSlot.captured.orderSnapshot
        assertEquals("Maria Silva", snapshot["customer"]["name"].asText())
        assertEquals("maria@exemplo.com", snapshot["customer"]["email"].asText())
        assertEquals("ABC1234", snapshot["vehicle"]["plate"].asText())
        assertEquals("Gol 1.6", snapshot["vehicle"]["model"].asText())
    }

    @Test
    fun `a redelivered OrderCreated is a no-op`() {
        every { writer.writeAll(any(), any(), any()) } returns TxResult.DUPLICATE

        useCase.enqueue(order)

        verify(exactly = 1) { writer.writeAll(any(), any(), any()) }
    }

    @Test
    fun `counts the AWAITING_DIAGNOSIS transition once`() {
        every { writer.writeAll(any(), any(), any()) } returns TxResult.SUCCESS

        useCase.enqueue(order)

        assertEquals(listOf(ExecutionStatus.AWAITING_DIAGNOSIS.name), metrics.statuses)
    }

    @Test
    fun `counts nothing when the OrderCreated is a duplicate`() {
        every { writer.writeAll(any(), any(), any()) } returns TxResult.DUPLICATE

        useCase.enqueue(order)

        assertEquals(emptyList<String>(), metrics.statuses)
    }
}
