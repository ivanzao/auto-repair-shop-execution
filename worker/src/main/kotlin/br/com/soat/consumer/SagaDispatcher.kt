package br.com.soat.consumer

import br.com.soat.event.ProcessedEventRepository
import br.com.soat.event.SagaEventHandler
import br.com.soat.event.model.EventEnvelope
import org.slf4j.LoggerFactory

/**
 * Despacha o envelope para os handlers cujo `eventType` casa. Dedup por `(eventId, consumerId)`:
 * só roda `handle` se `markProcessed` gravou agora. Evento sem handler = no-op (será deletado da fila).
 */
class SagaDispatcher(
    handlers: List<SagaEventHandler>,
    private val processed: ProcessedEventRepository,
) {
    private val logger = LoggerFactory.getLogger(SagaDispatcher::class.java)
    private val byType = handlers.groupBy { it.eventType }

    fun dispatch(env: EventEnvelope) {
        val matched = byType[env.eventType]
        if (matched.isNullOrEmpty()) {
            logger.debug("No handler for eventType=${env.eventType}, ignoring")
            return
        }
        matched.forEach { handler ->
            val consumerId = handler::class.simpleName!!
            if (processed.markProcessed(env.eventId, consumerId)) {
                handler.handle(env)
            } else {
                logger.info("Event ${env.eventId} already processed by $consumerId, skipping")
            }
        }
    }
}
