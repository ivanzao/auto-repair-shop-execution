package br.com.soat.execution

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.repository.ExecutionRepository
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Ao consumir PaymentConfirmed: marca a Execution como paga e a leva de RESERVED a IN_PROGRESS
 * (`queue()` + `start()`), gravando ExecutionStarted. Atômico via TransactWriteItems.
 * Idempotente por construção: se a Execution já passou de RESERVED, a transição inválida vira no-op.
 */
class ConfirmPaymentUseCase(
    private val executionRepository: ExecutionRepository,
    private val outbox: OutboxRepository,
    private val writer: TransactionalWriter,
    private val mapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(ConfirmPaymentUseCase::class.java)

    fun confirm(orderId: UUID, paymentId: String) {
        val execution = executionRepository.findByOrderId(orderId)
        if (execution == null) {
            logger.warn("PaymentConfirmed for unknown execution order={}", orderId)
            return
        }

        val started = try {
            execution.withPayment(paymentId).queue().start()
        } catch (_: InvalidExecutionTransitionException) {
            logger.info("Execution {} already started ({}), PaymentConfirmed is a no-op", orderId, execution.status)
            return
        }

        val event = EventEnvelope(
            eventType = SagaEventType.EXECUTION_STARTED,
            payload = mapper.createObjectNode().put("orderId", orderId.toString()),
        )

        writer.writeAll(
            puts = listOf(
                TxPut(executionRepository.putItem(started)),
                TxPut(outbox.putItem(event)),
            ),
        )
        logger.info("Execution started for order {}", orderId)
    }
}
