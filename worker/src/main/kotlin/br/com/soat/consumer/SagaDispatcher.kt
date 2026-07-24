package br.com.soat.consumer

import br.com.soat.event.ProcessedEventRepository
import br.com.soat.event.SagaEventHandler
import br.com.soat.event.model.EventEnvelope
import br.com.soat.tracing.Tracing
import io.opentelemetry.api.trace.SpanKind
import org.slf4j.LoggerFactory

class SagaDispatcher(
    handlers: List<SagaEventHandler>,
    private val processed: ProcessedEventRepository,
) {
    private val logger = LoggerFactory.getLogger(SagaDispatcher::class.java)
    private val byType = handlers.groupBy { it.eventType }

    fun dispatch(env: EventEnvelope, traceparent: String? = null) {
        val matched = byType[env.eventType]
        if (matched.isNullOrEmpty()) {
            logger.debug("No handler for eventType=${env.eventType}, ignoring")
            return
        }
        val span = Tracing.tracer
            .spanBuilder(env.eventType)
            .setSpanKind(SpanKind.CONSUMER)
            .setParent(Tracing.extractContext(traceparent))
            .startSpan()
        try {
            span.makeCurrent().use {
                matched.forEach { handler ->
                    val consumerId = handler::class.simpleName!!
                    if (processed.markProcessed(env.eventId, consumerId)) {
                        handler.handle(env)
                    } else {
                        logger.info("Event ${env.eventId} already processed by $consumerId, skipping")
                    }
                }
            }
        } finally {
            span.end()
        }
    }
}
