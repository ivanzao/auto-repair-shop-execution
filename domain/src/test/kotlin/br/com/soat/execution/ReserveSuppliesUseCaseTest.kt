package br.com.soat.execution

import br.com.soat.config.Config
import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.model.OrderCreatedPayload
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.supply.model.Supply
import br.com.soat.supply.repository.SupplyRepository
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

class ReserveSuppliesUseCaseTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val supplyRepository = mockk<SupplyRepository>()
    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)
    private val reservationRepository = mockk<ReservationRepository>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val writer = mockk<TransactionalWriter>()
    private val config = Config(mutableMapOf("reservation.ttl.days" to 7))

    private val useCase = ReserveSuppliesUseCase(
        supplyRepository, executionRepository, reservationRepository, outbox, writer, mapper, config,
    )

    private val supplyId = UUID.randomUUID()

    private fun payload(quantity: Int = 2) = OrderCreatedPayload(
        orderId = UUID.randomUUID(),
        customer = OrderCreatedPayload.Customer("Alice", "alice@example.com"),
        services = listOf(OrderCreatedPayload.ServiceLine("Troca de óleo", "149.90".toBigDecimal())),
        supplies = listOf(OrderCreatedPayload.SupplyLine(supplyId, quantity)),
    )

    private fun supply(stock: Int) = Supply(
        id = supplyId, name = "Filtro", description = null, quantityInStock = stock, price = "30.00".toBigDecimal(),
    )

    @Test
    fun `reserves supplies and emits SuppliesReserved carrying priced quote`() {
        every { supplyRepository.findAllByIds(listOf(supplyId)) } returns listOf(supply(stock = 5))
        every { writer.writeAll(any(), any(), any()) } returns TxResult.SUCCESS
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()
        val decrementsSlot = slot<List<SupplyDecrement>>()
        every { writer.writeAll(any(), capture(decrementsSlot), any()) } returns TxResult.SUCCESS

        useCase.reserve(payload(quantity = 2))

        assertEquals(2, decrementsSlot.captured.single().quantity)
        assertEquals(supplyId, decrementsSlot.captured.single().supplyId)

        val reserved = envSlot.captured
        assertEquals(SagaEventType.SUPPLIES_RESERVED, reserved.eventType)
        val p = reserved.payload
        assertEquals("Alice", p["customer"]["name"].asText())
        assertEquals("alice@example.com", p["customer"]["email"].asText())
        assertEquals("Troca de óleo", p["services"][0]["name"].asText())
        assertEquals(supplyId.toString(), p["supplies"][0]["id"].asText())
        assertEquals("Filtro", p["supplies"][0]["name"].asText())
        assertEquals(2, p["supplies"][0]["quantity"].asInt())
        assertEquals(0, "30.00".toBigDecimal().compareTo(p["supplies"][0]["unitPrice"].decimalValue()))
        assertEquals(0, "209.90".toBigDecimal().compareTo(p["totalAmount"].decimalValue()))
        UUID.fromString(p["reservationId"].asText())
    }

    @Test
    fun `emits PartsUnavailable on insufficient stock without touching the writer`() {
        every { supplyRepository.findAllByIds(listOf(supplyId)) } returns listOf(supply(stock = 1))
        val envSlot = slot<EventEnvelope>()
        every { outbox.save(capture(envSlot)) } returns Unit

        useCase.reserve(payload(quantity = 2))

        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
        assertEquals(SagaEventType.PARTS_UNAVAILABLE, envSlot.captured.eventType)
        val missing = envSlot.captured.payload["missingSupplies"][0]
        assertEquals(2, missing["requested"].asInt())
        assertEquals(1, missing["available"].asInt())
    }

    @Test
    fun `emits PartsUnavailable when writer reports stock conflict`() {
        every { supplyRepository.findAllByIds(listOf(supplyId)) } returns listOf(supply(stock = 5))
        every { writer.writeAll(any(), any(), any()) } returns TxResult.STOCK_CONFLICT
        val envSlot = slot<EventEnvelope>()
        every { outbox.save(capture(envSlot)) } returns Unit

        useCase.reserve(payload(quantity = 2))

        assertEquals(SagaEventType.PARTS_UNAVAILABLE, envSlot.captured.eventType)
    }
}
