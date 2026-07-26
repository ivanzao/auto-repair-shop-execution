package br.com.soat.execution

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.DomainEventType
import br.com.soat.execution.exception.ExecutionNotFoundException
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.MetricsPort
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.slf4j.LoggerFactory

class ExecutionLifecycleUseCase(
    private val executionRepository: ExecutionRepository,
    private val outbox: OutboxRepository,
    private val writer: TransactionalWriter,
    private val mapper: ObjectMapper,
    private val metrics: MetricsPort,
) {
    private val logger = LoggerFactory.getLogger(ExecutionLifecycleUseCase::class.java)

    fun get(orderId: UUID): Execution =
        executionRepository.findByOrderId(orderId) ?: throw ExecutionNotFoundException(orderId)

    fun listByStatus(status: ExecutionStatus): List<Execution> = executionRepository.findByStatus(status)

    fun start(orderId: UUID): Execution =
        transition(orderId, DomainEventType.EXECUTION_STARTED) { it.start() }

    fun finish(orderId: UUID): Execution =
        transition(orderId, DomainEventType.EXECUTION_FINISHED) { it.finish() }

    fun fail(orderId: UUID, reason: String): Execution {
        val execution = get(orderId)
        val failed = execution.fail()
        val payload = mapper.createObjectNode()
            .put("orderId", orderId.toString())
            .put("reason", reason)
        execution.paymentId?.let { payload.put("paymentId", it) }
        persist(failed, EventEnvelope(eventType = DomainEventType.EXECUTION_FAILED, payload = payload))
        logger.info("Execution failed for order {} ({})", orderId, reason)
        return failed
    }

    private fun transition(orderId: UUID, eventType: String, apply: (Execution) -> Execution): Execution {
        val execution = get(orderId)
        val next = apply(execution)
        val event = EventEnvelope(
            eventType = eventType,
            payload = mapper.createObjectNode().put("orderId", orderId.toString()),
        )
        persist(next, event)
        logger.info("Execution {} -> {} for order {}", execution.status, next.status, orderId)
        return next
    }

    private fun persist(execution: Execution, event: EventEnvelope) {
        writer.writeAll(
            puts = listOf(
                TxPut(executionRepository.putItem(execution)),
                TxPut(outbox.putItem(event)),
            ),
        )
        metrics.executionStatusChanged(execution.status.name)
    }
}
