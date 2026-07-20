package br.com.soat.consumer

import br.com.soat.event.ProcessedEventRepository
import br.com.soat.event.SagaEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.util.UUID

class SagaDispatcherTest {

    private val mapper = jacksonObjectMapper()
    private val processed = mockk<ProcessedEventRepository>()

    private fun handler(type: String) = mockk<SagaEventHandler>(relaxed = true).also {
        every { it.eventType } returns type
    }

    private fun envelope(type: String) = EventEnvelope(
        eventId = UUID.randomUUID(),
        eventType = type,
        payload = mapper.createObjectNode(),
    )

    @Test
    fun `dispatches to matching handler when not yet processed`() {
        val h = handler(SagaEventType.ORDER_CREATED)
        val dispatcher = SagaDispatcher(listOf(h), processed)
        val env = envelope(SagaEventType.ORDER_CREATED)
        every { processed.markProcessed(env.eventId, any()) } returns true

        dispatcher.dispatch(env)

        verify(exactly = 1) { h.handle(env) }
        verify { processed.markProcessed(env.eventId, any()) }
    }

    @Test
    fun `skips handler when already processed (duplicate)`() {
        val h = handler(SagaEventType.ORDER_CREATED)
        val dispatcher = SagaDispatcher(listOf(h), processed)
        val env = envelope(SagaEventType.ORDER_CREATED)
        every { processed.markProcessed(env.eventId, any()) } returns false

        dispatcher.dispatch(env)

        verify(exactly = 0) { h.handle(any()) }
    }

    @Test
    fun `ignores event with no handler`() {
        val h = handler(SagaEventType.ORDER_CREATED)
        val dispatcher = SagaDispatcher(listOf(h), processed)
        val env = envelope(SagaEventType.PAYMENT_CONFIRMED)

        dispatcher.dispatch(env)

        verify(exactly = 0) { h.handle(any()) }
        verify(exactly = 0) { processed.markProcessed(any(), any()) }
    }
}
