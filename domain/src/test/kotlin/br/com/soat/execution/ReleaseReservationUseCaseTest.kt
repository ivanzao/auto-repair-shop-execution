package br.com.soat.execution

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.DomainEventType
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.RecordingMetrics
import br.com.soat.reservation.model.Reservation
import br.com.soat.reservation.model.ReservationLine
import br.com.soat.reservation.model.ReservationStatus
import br.com.soat.reservation.repository.ReservationRepository
import com.fasterxml.jackson.databind.node.MissingNode
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ReleaseReservationUseCaseTest {

    private val reservationRepository = mockk<ReservationRepository>(relaxed = true)
    private val executionRepository = mockk<ExecutionRepository>(relaxed = true)
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val writer = mockk<TransactionalWriter>(relaxed = true)
    private val metrics = RecordingMetrics()
    private val useCase =
        ReleaseReservationUseCase(reservationRepository, executionRepository, outbox, writer, metrics)

    private val orderId = UUID.randomUUID()
    private val reservationId = UUID.randomUUID()
    private val supplyId = UUID.randomUUID()

    private fun reservation(status: ReservationStatus) = Reservation(
        id = reservationId,
        orderId = orderId,
        status = status,
        lines = listOf(ReservationLine(supplyId, 2)),
        expiresAt = Instant.now().plusSeconds(3600),
    )

    @Test
    fun `restores stock, deactivates reservation and cancels execution`() {
        every { reservationRepository.findById(reservationId) } returns reservation(ReservationStatus.ACTIVE)
        every { executionRepository.findByOrderId(orderId) } returns
            Execution(orderId = orderId, status = ExecutionStatus.RESERVED, orderSnapshot = MissingNode.getInstance())
        val incSlot = slot<List<SupplyIncrement>>()
        val execSlot = slot<Execution>()
        every { executionRepository.putItem(capture(execSlot)) } returns emptyMap()
        every { writer.writeAll(any(), any(), capture(incSlot)) } returns TxResult.SUCCESS

        useCase.release(reservationId)

        assertEquals(2, incSlot.captured.single().quantity)
        assertEquals(supplyId, incSlot.captured.single().supplyId)
        assertEquals(ExecutionStatus.CANCELED, execSlot.captured.status)
    }

    @Test
    fun `no-op when reservation not active`() {
        every { reservationRepository.findById(reservationId) } returns reservation(ReservationStatus.RELEASED)

        useCase.release(reservationId)

        verify(exactly = 0) { writer.writeAll(any(), any(), any()) }
    }

    @Test
    fun `emits event when emit lambda provided`() {
        every { reservationRepository.findById(reservationId) } returns reservation(ReservationStatus.ACTIVE)
        every { executionRepository.findByOrderId(orderId) } returns
            Execution(orderId = orderId, status = ExecutionStatus.RESERVED, orderSnapshot = MissingNode.getInstance())
        val envSlot = slot<EventEnvelope>()
        every { outbox.putItem(capture(envSlot)) } returns emptyMap()

        useCase.release(reservationId) { execution ->
            EventEnvelope(
                eventType = DomainEventType.RESERVATION_EXPIRED,
                payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("orderId", execution.orderId.toString())
                    .put("reservationId", reservationId.toString()),
            )
        }

        assertEquals(DomainEventType.RESERVATION_EXPIRED, envSlot.captured.eventType)
    }

    @Test
    fun `counts the CANCELED transition when the reservation is released`() {
        every { reservationRepository.findById(reservationId) } returns reservation(ReservationStatus.ACTIVE)
        every { executionRepository.findByOrderId(orderId) } returns
            Execution(orderId = orderId, status = ExecutionStatus.RESERVED, orderSnapshot = MissingNode.getInstance())
        every { writer.writeAll(any(), any(), any()) } returns TxResult.SUCCESS

        val released = useCase.release(reservationId)

        assertEquals(true, released)
        assertEquals(listOf(ExecutionStatus.CANCELED.name), metrics.statuses)
    }

    @Test
    fun `counts nothing when the reservation is not active`() {
        every { reservationRepository.findById(reservationId) } returns reservation(ReservationStatus.RELEASED)

        val released = useCase.release(reservationId)

        assertEquals(false, released)
        assertEquals(emptyList<String>(), metrics.statuses)
    }
}
