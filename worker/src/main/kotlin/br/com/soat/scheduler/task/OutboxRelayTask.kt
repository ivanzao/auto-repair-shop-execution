package br.com.soat.scheduler.task

import br.com.soat.event.OutboxRepository
import br.com.soat.messaging.SnsPublisher
import br.com.soat.scheduler.ScheduledTask
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Relay outbox → SNS. Publica cada envelope pendente e o marca como publicado (remove do GSI PENDING).
 * Multi-réplica: se duas réplicas relayarem o mesmo item ambas publicam, mas o downstream deduplica por eventId.
 */
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
                sns.publish(body = body, eventType = env.eventType)
                outbox.markPublished(env.eventId)
            } catch (e: Exception) {
                logger.error("Failed to relay outbox event ${env.eventId}", e)
            }
        }
    }

    override fun getName() = "outbox-relay"
    override fun getIntervalInSeconds() = 5L
}
