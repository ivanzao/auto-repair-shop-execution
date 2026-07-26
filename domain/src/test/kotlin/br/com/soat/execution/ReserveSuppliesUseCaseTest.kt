package br.com.soat.execution

import br.com.soat.config.Config
import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.DomainEventType
import br.com.soat.event.model.EventEnvelope
import br.com.soat.execution.exception.ExecutionNotFoundException
import br.com.soat.execution.model.DiagnosisRequest
import br.com.soat.execution.model.DiagnosisResult
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.RecordingMetrics
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.service.exception.ServiceNotFoundException
import br.com.soat.service.model.Service
import br.com.soat.service.repository.ServiceRepository
import br.com.soat.shared.model.SupplyRequirement
import br.com.soat.shared.model.User
import br.com.soat.supply.model.Supply
import br.com.soat.supply.repository.SupplyRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
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

private const val DIAGNOSE_FINISHED = """
{
  "orderId": "11111111-1111-1111-1111-111111111111",
  "reservationId": "44444444-4444-4444-4444-444444444444",
  "diagnosedBy": {
    "id": "00000000-0000-0000-0000-000000000003",
    "document": "12345678909"
  },
  "customer": {
    "name": "Maria Silva",
    "email": "maria@exemplo.com"
  },
  "services": [
    { "id": "55555555-5555-5555-5555-555555555555", "name": "Troca de oleo", "price": 100.00 }
  ],
  "supplies": [
    { "id": "66666666-6666-6666-6666-666666666666", "name": "Filtro de oleo", "quantity": 2, "unitPrice": 30.00 }
  ],
  "totalAmount": 160.00
}
"""

private const val SUPPLIES_UNAVAILABLE = """
{
  "orderId": "11111111-1111-1111-1111-111111111111",
  "missingSupplies": [
    {
      "supplyId": "66666666-6666-6666-6666-666666666666",
      "name": "Filtro de oleo",
      "requested": 4,
      "available": 1
    }
  ]
}
"""

class ReserveSuppliesUseCaseTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val serviceRepository = mockk<ServiceRepository>()
    private val supplyRepository = mockk<SupplyRepository>()
    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)
    private val reservationRepository = mockk<ReservationRepository>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val writer = mockk<TransactionalWriter>()
    private val config = Config(mutableMapOf("reservation.ttl.days" to 7))

    private val metrics = RecordingMetrics()

    private val useCase = ReserveSuppliesUseCase(
        serviceRepository, supplyRepository, executionRepository, reservationRepository,
        outbox, writer, mapper, config, metrics,
    )

    private val orderId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val serviceId = UUID.fromString("55555555-5555-5555-5555-555555555555")
    private val supplyId = UUID.fromString("66666666-6666-6666-6666-666666666666")
    private val diagnosedBy = User(
        id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        document = "12345678909",
    )

    private val expectedFinished = mapper.readTree(DIAGNOSE_FINISHED)
    private val expectedUnavailable = mapper.readTree(SUPPLIES_UNAVAILABLE)

    private fun execution(status: ExecutionStatus = ExecutionStatus.AWAITING_DIAGNOSIS) = Execution(
        orderId = orderId,
        status = status,
        orderSnapshot = mapper.readTree(ORDER_CREATED),
    )

    private fun service() = Service(
        id = serviceId, name = "Troca de oleo", description = null, price = "100.00".toBigDecimal(),
    )

    private fun supply(stock: Int) = Supply(
        id = supplyId, name = "Filtro de oleo", description = null,
        quantityInStock = stock, price = "30.00".toBigDecimal(),
    )

    private fun request(quantity: Int = 2) = DiagnosisRequest(
        services = listOf(serviceId),
        supplies = listOf(SupplyRequirement(supplyId, quantity)),
    )

    @Test
    fun `finishing the diagnosis reserves supplies and emits DiagnoseFinished as the frozen contract`() {
        every { executionRepository.findByOrderId(orderId) } returns execution()
        every { serviceRepository.findAllByIds(listOf(serviceId)) } returns listOf(service())
        every { supplyRepository.findAllByIds(listOf(supplyId)) } returns listOf(supply(stock = 5))
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()
        val decrementsSlot = slot<List<SupplyDecrement>>()
        every { writer.writeAll(any(), capture(decrementsSlot), any()) } returns TxResult.SUCCESS

        val result = useCase.finishDiagnosis(orderId, diagnosedBy, request(quantity = 2))

        assertTrue(result is DiagnosisResult.Reserved)
        assertEquals(ExecutionStatus.RESERVED, (result as DiagnosisResult.Reserved).execution.status)
        assertEquals(diagnosedBy, result.execution.diagnosedBy)
        assertEquals(supplyId, decrementsSlot.captured.single().supplyId)
        assertEquals(2, decrementsSlot.captured.single().quantity)

        val payload = envSlot.captured.payload
        assertEquals(DomainEventType.DIAGNOSE_FINISHED, envSlot.captured.eventType)
        assertEquals(
            listOf("orderId", "reservationId", "diagnosedBy", "customer", "services", "supplies", "totalAmount"),
            payload.fieldNames().asSequence().toList(),
        )
        assertEquals(expectedFinished["orderId"], payload["orderId"])
        UUID.fromString(payload["reservationId"].asText())
        assertEquals(expectedFinished["diagnosedBy"], payload["diagnosedBy"])
        assertEquals(expectedFinished["customer"], payload["customer"])
        assertEquals(expectedFinished["services"][0]["id"], payload["services"][0]["id"])
        assertEquals(expectedFinished["services"][0]["name"], payload["services"][0]["name"])
        assertEquals(
            0,
            expectedFinished["services"][0]["price"].decimalValue()
                .compareTo(payload["services"][0]["price"].decimalValue()),
        )
        assertEquals(expectedFinished["supplies"][0]["id"], payload["supplies"][0]["id"])
        assertEquals(expectedFinished["supplies"][0]["name"], payload["supplies"][0]["name"])
        assertEquals(expectedFinished["supplies"][0]["quantity"], payload["supplies"][0]["quantity"])
        assertEquals(
            0,
            expectedFinished["supplies"][0]["unitPrice"].decimalValue()
                .compareTo(payload["supplies"][0]["unitPrice"].decimalValue()),
        )
        assertEquals(
            0,
            expectedFinished["totalAmount"].decimalValue().compareTo(payload["totalAmount"].decimalValue()),
        )
        assertEquals(1, metrics.reserved)
        assertEquals(0, metrics.unavailable)
        assertEquals(listOf(ExecutionStatus.RESERVED.name), metrics.statuses)
    }

    @Test
    fun `insufficient stock cancels the execution and emits SuppliesUnavailable as the frozen contract`() {
        every { executionRepository.findByOrderId(orderId) } returns execution()
        every { serviceRepository.findAllByIds(listOf(serviceId)) } returns listOf(service())
        every { supplyRepository.findAllByIds(listOf(supplyId)) } returns listOf(supply(stock = 1))
        val execSlot = slot<Execution>()
        every { executionRepository.putItem(capture(execSlot)) } returns emptyMap()
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()
        val decrementsSlot = slot<List<SupplyDecrement>>()
        every { writer.writeAll(any(), capture(decrementsSlot), any()) } returns TxResult.SUCCESS

        val result = useCase.finishDiagnosis(orderId, diagnosedBy, request(quantity = 4))

        assertTrue(result is DiagnosisResult.Unavailable)
        assertEquals(1, (result as DiagnosisResult.Unavailable).missing.single().available)
        assertEquals("Filtro de oleo", result.missing.single().name)
        assertEquals(ExecutionStatus.CANCELED, execSlot.captured.status)
        assertTrue(decrementsSlot.captured.isEmpty())

        assertEquals(DomainEventType.SUPPLIES_UNAVAILABLE, envSlot.captured.eventType)
        assertEquals(expectedUnavailable, envSlot.captured.payload)
        assertEquals(1, metrics.unavailable)
        assertEquals(0, metrics.reserved)
        assertEquals(listOf(ExecutionStatus.CANCELED.name), metrics.statuses)
    }

    @Test
    fun `stock conflict reported by the writer cancels and emits SuppliesUnavailable`() {
        every { executionRepository.findByOrderId(orderId) } returns execution()
        every { serviceRepository.findAllByIds(listOf(serviceId)) } returns listOf(service())
        every { supplyRepository.findAllByIds(listOf(supplyId)) } returnsMany
            listOf(listOf(supply(stock = 5)), listOf(supply(stock = 1)))
        every { writer.writeAll(any(), any(), any()) } returnsMany
            listOf(TxResult.STOCK_CONFLICT, TxResult.SUCCESS)
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        val result = useCase.finishDiagnosis(orderId, diagnosedBy, request(quantity = 4))

        assertTrue(result is DiagnosisResult.Unavailable)
        assertEquals(DomainEventType.SUPPLIES_UNAVAILABLE, envSlot.captured.eventType)
        assertEquals(expectedUnavailable, envSlot.captured.payload)
    }

    @Test
    fun `unknown order is rejected`() {
        every { executionRepository.findByOrderId(orderId) } returns null

        assertThrows(ExecutionNotFoundException::class.java) {
            useCase.finishDiagnosis(orderId, diagnosedBy, request())
        }
    }

    @Test
    fun `unknown service is rejected`() {
        every { executionRepository.findByOrderId(orderId) } returns execution()
        every { serviceRepository.findAllByIds(listOf(serviceId)) } returns emptyList()

        assertThrows(ServiceNotFoundException::class.java) {
            useCase.finishDiagnosis(orderId, diagnosedBy, request())
        }
    }

    @Test
    fun `repeated supply lines are aggregated into a single decrement`() {
        every { executionRepository.findByOrderId(orderId) } returns execution()
        every { serviceRepository.findAllByIds(listOf(serviceId)) } returns listOf(service())
        every { supplyRepository.findAllByIds(listOf(supplyId)) } returns listOf(supply(stock = 5))
        every { outbox.putItem(any()) } returns emptyMap()
        val decrementsSlot = slot<List<SupplyDecrement>>()
        every { writer.writeAll(any(), capture(decrementsSlot), any()) } returns TxResult.SUCCESS

        useCase.finishDiagnosis(
            orderId,
            diagnosedBy,
            DiagnosisRequest(
                services = listOf(serviceId, serviceId),
                supplies = listOf(SupplyRequirement(supplyId, 1), SupplyRequirement(supplyId, 2)),
            ),
        )

        assertEquals(1, decrementsSlot.captured.size)
        assertEquals(3, decrementsSlot.captured.single().quantity)
    }
}
