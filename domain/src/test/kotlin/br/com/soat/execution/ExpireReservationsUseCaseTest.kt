package br.com.soat.execution

import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.reservation.model.Reservation
import br.com.soat.reservation.model.ReservationLine
import br.com.soat.reservation.repository.ReservationRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class ExpireReservationsUseCaseTest {

    private val mapper = jacksonObjectMapper()
    private val reservationRepository = mockk<ReservationRepository>()
    private val release = mockk<ReleaseReservationUseCase>(relaxed = true)
    private val fixedNow = Instant.parse("2026-07-20T00:00:00Z")
    private val useCase = ExpireReservationsUseCase(reservationRepository, release, mapper) { fixedNow }

    private fun reservation() = Reservation(
        orderId = UUID.randomUUID(),
        lines = listOf(ReservationLine(UUID.randomUUID(), 1)),
        expiresAt = fixedNow.minusSeconds(60),
    )

    @Test
    fun `releases each expired reservation`() {
        val r1 = reservation()
        val r2 = reservation()
        every { reservationRepository.findActiveExpiredBefore(fixedNow, 25) } returns listOf(r1, r2)

        useCase.run()

        verify(exactly = 1) { release.release(r1.id, any()) }
        verify(exactly = 1) { release.release(r2.id, any()) }
    }

    @Test
    fun `emit lambda builds ReservationExpired payload`() {
        val r = reservation()
        every { reservationRepository.findActiveExpiredBefore(fixedNow, 25) } returns listOf(r)
        val emitSlot = slot<(br.com.soat.execution.model.Execution) -> EventEnvelope?>()
        every { release.release(eq(r.id), capture(emitSlot)) } returns Unit

        useCase.run()

        val execution = br.com.soat.execution.model.Execution(
            orderId = r.orderId,
            status = br.com.soat.execution.model.ExecutionStatus.RESERVED,
            orderSnapshot = com.fasterxml.jackson.databind.node.MissingNode.getInstance(),
        )
        val event = emitSlot.captured(execution)!!
        assertEquals(SagaEventType.RESERVATION_EXPIRED, event.eventType)
        assertEquals(r.orderId.toString(), event.payload["orderId"].asText())
        assertEquals(r.id.toString(), event.payload["reservationId"].asText())
    }

    @Test
    fun `no-op when nothing expired`() {
        every { reservationRepository.findActiveExpiredBefore(fixedNow, 25) } returns emptyList()
        useCase.run()
        verify(exactly = 0) { release.release(any(), any()) }
    }
}
