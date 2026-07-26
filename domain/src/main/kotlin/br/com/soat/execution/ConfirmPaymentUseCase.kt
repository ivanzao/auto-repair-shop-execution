package br.com.soat.execution

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.MetricsPort
import java.util.UUID
import org.slf4j.LoggerFactory

class ConfirmPaymentUseCase(
    private val executionRepository: ExecutionRepository,
    private val writer: TransactionalWriter,
    private val metrics: MetricsPort,
) {
    private val logger = LoggerFactory.getLogger(ConfirmPaymentUseCase::class.java)

    fun confirm(orderId: UUID, paymentId: String) {
        val execution = executionRepository.findByOrderId(orderId)
        if (execution == null) {
            logger.warn("PaymentConfirmed for unknown execution order={}", orderId)
            return
        }

        val enqueued = try {
            execution.withPayment(paymentId).enqueue()
        } catch (_: InvalidExecutionTransitionException) {
            logger.info("Execution {} already left RESERVED ({}), PaymentConfirmed is a no-op", orderId, execution.status)
            return
        }

        writer.writeAll(
            puts = listOf(
                TxPut(
                    item = executionRepository.putItem(enqueued),
                    conditionExpression = "#st = :reserved",
                    expressionAttributeValues = mapOf(
                        ":reserved" to AttributeValue.S(ExecutionStatus.RESERVED.name),
                    ),
                    expressionAttributeNames = mapOf("#st" to "status"),
                ),
            ),
        )
        metrics.executionStatusChanged(ExecutionStatus.ENQUEUED.name)
        logger.info("Execution enqueued for order {}", orderId)
    }
}
