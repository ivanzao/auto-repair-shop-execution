package br.com.soat.scheduler.task

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.DomainEventType
import br.com.soat.messaging.SnsPublisher
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class OutboxRelayTaskTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private val outbox = mockk<OutboxRepository>(relaxed = true)
    private val sns = mockk<SnsPublisher>(relaxed = true)
    private val task = OutboxRelayTask(outbox, sns, mapper)

    @Test
    fun `publishes pending envelope and marks published`() {
        val env = EventEnvelope(
            eventId = UUID.randomUUID(),
            eventType = DomainEventType.DIAGNOSE_FINISHED,
            payload = mapper.createObjectNode().put("orderId", "o1"),
        )
        every { outbox.pending(10) } returns listOf(env)

        task.execute()

        verify { sns.publish(body = any(), eventType = DomainEventType.DIAGNOSE_FINISHED, traceparent = null) }
        verify { outbox.markPublished(env.eventId) }
    }

    @Test
    fun `does not mark published when publish fails`() {
        val env = EventEnvelope(
            eventType = DomainEventType.DIAGNOSE_FINISHED,
            payload = mapper.createObjectNode(),
        )
        every { outbox.pending(10) } returns listOf(env)
        every { sns.publish(any(), any(), any()) } throws RuntimeException("sns down")

        task.execute()

        verify(exactly = 0) { outbox.markPublished(any()) }
    }
}
