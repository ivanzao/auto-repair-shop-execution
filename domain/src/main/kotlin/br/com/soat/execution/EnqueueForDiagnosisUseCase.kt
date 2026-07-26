package br.com.soat.execution

import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.model.OrderCreatedPayload
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.MetricsPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

class EnqueueForDiagnosisUseCase(
    private val executionRepository: ExecutionRepository,
    private val writer: TransactionalWriter,
    private val mapper: ObjectMapper,
    private val metrics: MetricsPort,
) {
    private val logger = LoggerFactory.getLogger(EnqueueForDiagnosisUseCase::class.java)

    fun enqueue(order: OrderCreatedPayload) {
        val execution = Execution(
            orderId = order.orderId,
            status = ExecutionStatus.AWAITING_DIAGNOSIS,
            orderSnapshot = mapper.valueToTree(order),
        )

        val result = writer.writeAll(
            puts = listOf(
                TxPut(executionRepository.putItem(execution), conditionExpression = "attribute_not_exists(pk)"),
            ),
        )

        when (result) {
            TxResult.SUCCESS -> {
                metrics.executionStatusChanged(ExecutionStatus.AWAITING_DIAGNOSIS.name)
                logger.info("Order {} enqueued for diagnosis", order.orderId)
            }
            TxResult.DUPLICATE ->
                logger.info("OrderCreated {} already enqueued for diagnosis (idempotent no-op)", order.orderId)
            TxResult.STOCK_CONFLICT ->
                logger.warn("Unexpected STOCK_CONFLICT enqueueing order {} for diagnosis", order.orderId)
        }
    }
}
