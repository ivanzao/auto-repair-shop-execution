package br.com.soat.scheduler.task

import br.com.soat.event.OutboxRepository
import br.com.soat.messaging.SnsPublisher
import br.com.soat.scheduler.ScheduledTask
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

class OutboxRelayTask(
    private val outbox: OutboxRepository,
    private val sns: SnsPublisher,
    private val mapper: ObjectMapper,
) : ScheduledTask {
    private val logger = LoggerFactory.getLogger(OutboxRelayTask::class.java)

    override fun execute() {
        outbox.pending(10).forEach { env ->
            try {
                val body = mapper.writeValueAsString(env)
                sns.publish(body = body, eventType = env.eventType, traceparent = env.traceparent)
                outbox.markPublished(env.eventId)
            } catch (e: Exception) {
                logger.error("Failed to relay outbox event ${env.eventId}", e)
            }
        }
    }

    override fun getName() = "outbox-relay"
    override fun getIntervalInSeconds() = 5L
}
