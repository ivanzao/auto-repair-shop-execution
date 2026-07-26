package br.com.soat.consumer

import br.com.soat.event.ProcessedEventRepository
import br.com.soat.event.DomainEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.DomainEventType
import br.com.soat.metric.MetricsPort
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class DomainEventDispatcherTest {

    private val mapper = jacksonObjectMapper()
    private val processed = mockk<ProcessedEventRepository>()
    private val metrics = mockk<MetricsPort>(relaxed = true)

    private fun handler(type: String) = mockk<DomainEventHandler>(relaxed = true).also {
        every { it.eventType } returns type
    }

    private fun envelope(type: String) = EventEnvelope(
        eventId = UUID.randomUUID(),
        eventType = type,
        payload = mapper.createObjectNode(),
    )

    @Test
    fun `dispatches to matching handler when not yet processed`() {
        val h = handler(DomainEventType.ORDER_CREATED)
        val dispatcher = DomainEventDispatcher(listOf(h), processed, metrics)
        val env = envelope(DomainEventType.ORDER_CREATED)
        every { processed.markProcessed(env.eventId, any()) } returns true

        dispatcher.dispatch(env)

        verify(exactly = 1) { h.handle(env) }
        verify { processed.markProcessed(env.eventId, any()) }
        verify(exactly = 1) { metrics.inboundEventApplied() }
    }

    @Test
    fun `skips handler when already processed (duplicate)`() {
        val h = handler(DomainEventType.ORDER_CREATED)
        val dispatcher = DomainEventDispatcher(listOf(h), processed, metrics)
        val env = envelope(DomainEventType.ORDER_CREATED)
        every { processed.markProcessed(env.eventId, any()) } returns false

        dispatcher.dispatch(env)

        verify(exactly = 0) { h.handle(any()) }
        verify(exactly = 0) { metrics.inboundEventApplied() }
    }

    @Test
    fun `continues the trace from the message traceparent inside the handler`() {
        val traceId = "0af7651916cd43dd8448eb211c80319c"
        val seen = mutableListOf<String>()
        val h = handler(DomainEventType.ORDER_CREATED)
        every { h.handle(any()) } answers {
            seen += io.opentelemetry.api.trace.Span.current().spanContext.traceId
        }
        val dispatcher = DomainEventDispatcher(listOf(h), processed, metrics)
        val env = envelope(DomainEventType.ORDER_CREATED)
        every { processed.markProcessed(env.eventId, any()) } returns true

        dispatcher.dispatch(env, "00-$traceId-b7ad6b7169203331-01")

        assertEquals(listOf(traceId), seen)
    }

    @Test
    fun `ignores event with no handler`() {
        val h = handler(DomainEventType.ORDER_CREATED)
        val dispatcher = DomainEventDispatcher(listOf(h), processed, metrics)
        val env = envelope(DomainEventType.PAYMENT_CONFIRMED)

        dispatcher.dispatch(env)

        verify(exactly = 0) { h.handle(any()) }
        verify(exactly = 0) { processed.markProcessed(any(), any()) }
        verify(exactly = 0) { metrics.inboundEventApplied() }
    }
}
