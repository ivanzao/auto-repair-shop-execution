package br.com.soat.execution

import br.com.soat.config.Config
import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.model.OrderCreatedPayload
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.reservation.model.Reservation
import br.com.soat.reservation.model.ReservationLine
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.supply.model.Supply
import br.com.soat.supply.repository.SupplyRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

/**
 * Reserva de insumos ao consumir OrderCreated. Estoque suficiente → SuppliesReserved (repassando o
 * quote priced enriquecido com name/unitPrice do estoque local + totalAmount calculado). Estoque
 * insuficiente ou corrida perdida → PartsUnavailable. Idempotente: OrderCreated reentregue vira no-op
 * pela condição `attribute_not_exists(pk)` no item da Execution.
 */
class ReserveSuppliesUseCase(
    private val supplyRepository: SupplyRepository,
    private val executionRepository: ExecutionRepository,
    private val reservationRepository: ReservationRepository,
    private val outbox: OutboxRepository,
    private val writer: TransactionalWriter,
    private val mapper: ObjectMapper,
    private val config: Config,
) {
    private val logger = LoggerFactory.getLogger(ReserveSuppliesUseCase::class.java)

    private data class MissingSupply(val supplyId: UUID, val name: String, val requested: Int, val available: Int)

    fun reserve(order: OrderCreatedPayload) {
        val orderId = order.orderId
        val required = order.supplies
        val supplies = supplyRepository.findAllByIds(required.map { it.id }).associateBy { it.id }

        val missing = computeMissing(required, supplies)
        if (missing.isNotEmpty()) {
            emitPartsUnavailable(orderId, missing)
            return
        }

        val reservationId = UUID.randomUUID()
        val ttlDays = config.getInt("reservation.ttl.days", 7).toLong()
        val reservation = Reservation(
            id = reservationId,
            orderId = orderId,
            lines = required.map { ReservationLine(it.id, it.quantity) },
            expiresAt = Instant.now().plus(Duration.ofDays(ttlDays)),
        )
        val execution = Execution(
            orderId = orderId,
            status = ExecutionStatus.RESERVED,
            reservationId = reservationId,
            orderSnapshot = mapper.valueToTree(order),
        )
        val suppliesReserved = EventEnvelope(
            eventType = SagaEventType.SUPPLIES_RESERVED,
            payload = buildSuppliesReservedPayload(order, supplies, reservationId),
        )

        val result = writer.writeAll(
            puts = listOf(
                TxPut(reservationRepository.putItem(reservation)),
                TxPut(executionRepository.putItem(execution), conditionExpression = "attribute_not_exists(pk)"),
                TxPut(outbox.putItem(suppliesReserved)),
            ),
            decrements = required.map { SupplyDecrement(it.id, it.quantity) },
        )

        when (result) {
            TxResult.SUCCESS ->
                logger.info("Supplies reserved for order {} (reservation {})", orderId, reservationId)
            TxResult.DUPLICATE ->
                logger.info("OrderCreated {} already reserved (idempotent no-op)", orderId)
            TxResult.STOCK_CONFLICT -> {
                val recomputed = computeMissing(required, supplyRepository.findAllByIds(required.map { it.id }).associateBy { it.id })
                emitPartsUnavailable(orderId, recomputed)
            }
        }
    }

    private fun computeMissing(
        required: List<OrderCreatedPayload.SupplyLine>,
        supplies: Map<UUID, Supply>,
    ): List<MissingSupply> = required.mapNotNull { req ->
        val supply = supplies[req.id]
        val available = supply?.quantityInStock ?: 0
        if (available < req.quantity) {
            MissingSupply(req.id, supply?.name ?: "?", req.quantity, available)
        } else {
            null
        }
    }

    private fun emitPartsUnavailable(orderId: UUID, missing: List<MissingSupply>) {
        val payload = mapper.createObjectNode()
        payload.put("orderId", orderId.toString())
        val arr = payload.putArray("missingSupplies")
        missing.forEach { m ->
            arr.addObject().apply {
                put("supplyId", m.supplyId.toString())
                put("name", m.name)
                put("requested", m.requested)
                put("available", m.available)
            }
        }
        outbox.save(EventEnvelope(eventType = SagaEventType.PARTS_UNAVAILABLE, payload = payload))
        logger.info("PartsUnavailable emitted for order {} ({} missing)", orderId, missing.size)
    }

    private fun buildSuppliesReservedPayload(
        order: OrderCreatedPayload,
        supplies: Map<UUID, Supply>,
        reservationId: UUID,
    ): JsonNode {
        val node = mapper.createObjectNode()
        node.put("orderId", order.orderId.toString())
        node.put("reservationId", reservationId.toString())
        node.putObject("customer").apply {
            put("name", order.customer.name)
            put("email", order.customer.email)
        }
        val servicesArr = node.putArray("services")
        order.services.forEach { s ->
            servicesArr.addObject().apply {
                put("name", s.name)
                put("price", s.price)
            }
        }
        val suppliesArr = node.putArray("supplies")
        var totalAmount = order.services.fold(BigDecimal.ZERO) { acc, s -> acc + s.price }
        order.supplies.forEach { line ->
            val supply = supplies.getValue(line.id)
            val lineTotal = supply.price.multiply(BigDecimal(line.quantity))
            totalAmount = totalAmount + lineTotal
            suppliesArr.addObject().apply {
                put("id", line.id.toString())
                put("name", supply.name)
                put("quantity", line.quantity)
                put("unitPrice", supply.price)
            }
        }
        node.put("totalAmount", totalAmount)
        return node
    }
}
