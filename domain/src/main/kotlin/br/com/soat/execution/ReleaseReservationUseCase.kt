package br.com.soat.execution

import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.execution.model.Execution
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.reservation.model.ReservationStatus
import br.com.soat.reservation.repository.ReservationRepository
import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import java.util.UUID
import org.slf4j.LoggerFactory

class ReleaseReservationUseCase(
    private val reservationRepository: ReservationRepository,
    private val executionRepository: ExecutionRepository,
    private val outbox: OutboxRepository,
    private val writer: TransactionalWriter,
) {
    private val logger = LoggerFactory.getLogger(ReleaseReservationUseCase::class.java)

    fun release(reservationId: UUID, emit: (Execution) -> EventEnvelope? = { null }) {
        val reservation = reservationRepository.findById(reservationId)
        if (reservation == null) {
            logger.info("Reservation {} not found, nothing to release", reservationId)
            return
        }
        if (reservation.status != ReservationStatus.ACTIVE) {
            logger.info("Reservation {} not ACTIVE ({}), release is a no-op", reservationId, reservation.status)
            return
        }

        val execution = executionRepository.findByOrderId(reservation.orderId)
        val canceled = execution?.let { runCatching { it.cancel() }.getOrDefault(it) }

        val released = reservation.release()
        val outEvent = canceled?.let(emit)

        val puts = buildList {
            add(
                TxPut(
                    item = reservationRepository.putItem(released),
                    conditionExpression = "#st = :active",
                    expressionAttributeValues = mapOf(":active" to AttributeValue.S(ReservationStatus.ACTIVE.name)),
                    expressionAttributeNames = mapOf("#st" to "status"),
                ),
            )
            canceled?.let { add(TxPut(executionRepository.putItem(it))) }
            outEvent?.let { add(TxPut(outbox.putItem(it))) }
        }
        val increments = reservation.lines.map { SupplyIncrement(it.supplyId, it.quantity) }

        when (writer.writeAll(puts = puts, increments = increments)) {
            TxResult.SUCCESS ->
                logger.info("Reservation {} released and stock restored", reservationId)
            TxResult.DUPLICATE ->
                logger.info("Reservation {} concurrently released (no-op)", reservationId)
            TxResult.STOCK_CONFLICT ->
                logger.warn("Unexpected STOCK_CONFLICT releasing reservation {}", reservationId)
        }
    }
}
