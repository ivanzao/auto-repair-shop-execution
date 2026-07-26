package br.com.soat.execution

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import br.com.soat.config.Config
import br.com.soat.event.OutboxRepository
import br.com.soat.event.model.DomainEventType
import br.com.soat.event.model.EventEnvelope
import br.com.soat.execution.exception.ExecutionNotFoundException
import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.model.DiagnosisRequest
import br.com.soat.execution.model.DiagnosisResult
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.execution.model.MissingSupply
import br.com.soat.execution.model.OrderCreatedPayload
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.metric.MetricsPort
import br.com.soat.reservation.model.Reservation
import br.com.soat.reservation.model.ReservationLine
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.service.exception.ServiceNotFoundException
import br.com.soat.service.model.Service
import br.com.soat.service.repository.ServiceRepository
import br.com.soat.shared.model.SupplyRequirement
import br.com.soat.shared.model.User
import br.com.soat.supply.model.Supply
import br.com.soat.supply.repository.SupplyRepository
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory

class ReserveSuppliesUseCase(
    private val serviceRepository: ServiceRepository,
    private val supplyRepository: SupplyRepository,
    private val executionRepository: ExecutionRepository,
    private val reservationRepository: ReservationRepository,
    private val outbox: OutboxRepository,
    private val writer: TransactionalWriter,
    private val mapper: ObjectMapper,
    private val config: Config,
    private val metrics: MetricsPort,
) {
    private val logger = LoggerFactory.getLogger(ReserveSuppliesUseCase::class.java)

    fun finishDiagnosis(orderId: UUID, diagnosedBy: User, request: DiagnosisRequest): DiagnosisResult {
        val execution = executionRepository.findByOrderId(orderId) ?: throw ExecutionNotFoundException(orderId)

        val serviceIds = request.services.distinct()
        val required = request.supplies
            .groupBy { it.supplyId }
            .map { (supplyId, lines) -> SupplyRequirement(supplyId, lines.sumOf { it.quantity }) }

        val services = resolveServices(serviceIds)
        val supplies = supplyRepository.findAllByIds(required.map { it.supplyId }).associateBy { it.id }

        val missing = computeMissing(required, supplies)
        if (missing.isNotEmpty()) {
            cancelWithSuppliesUnavailable(execution, missing)
            return DiagnosisResult.Unavailable(missing)
        }

        val reservationId = UUID.randomUUID()
        val ttlDays = config.getInt("reservation.ttl.days", 7).toLong()
        val reservation = Reservation(
            id = reservationId,
            orderId = orderId,
            lines = required.map { ReservationLine(it.supplyId, it.quantity) },
            expiresAt = Instant.now().plus(Duration.ofDays(ttlDays)),
        )
        val reserved = execution.reserve(reservationId, diagnosedBy)
        val diagnoseFinished = EventEnvelope(
            eventType = DomainEventType.DIAGNOSE_FINISHED,
            payload = buildDiagnoseFinishedPayload(reserved, reservationId, diagnosedBy, services, required, supplies),
        )

        val result = writer.writeAll(
            puts = listOf(
                TxPut(reservationRepository.putItem(reservation)),
                TxPut(
                    item = executionRepository.putItem(reserved),
                    conditionExpression = "#st = :awaiting",
                    expressionAttributeValues = mapOf(
                        ":awaiting" to AttributeValue.S(ExecutionStatus.AWAITING_DIAGNOSIS.name),
                    ),
                    expressionAttributeNames = mapOf("#st" to "status"),
                ),
                TxPut(outbox.putItem(diagnoseFinished)),
            ),
            decrements = required.map { SupplyDecrement(it.supplyId, it.quantity) },
        )

        return when (result) {
            TxResult.SUCCESS -> {
                metrics.suppliesReserved()
                metrics.executionStatusChanged(ExecutionStatus.RESERVED.name)
                logger.info("Diagnosis finished for order {} (reservation {})", orderId, reservationId)
                DiagnosisResult.Reserved(reserved)
            }

            TxResult.DUPLICATE -> {
                logger.info("Diagnosis for order {} lost the race, execution left AWAITING_DIAGNOSIS", orderId)
                throw InvalidExecutionTransitionException(orderId, execution.status, ExecutionStatus.RESERVED)
            }

            TxResult.STOCK_CONFLICT -> {
                val recomputed = computeMissing(
                    required,
                    supplyRepository.findAllByIds(required.map { it.supplyId }).associateBy { it.id },
                )
                cancelWithSuppliesUnavailable(execution, recomputed)
                DiagnosisResult.Unavailable(recomputed)
            }
        }
    }

    private fun resolveServices(serviceIds: List<UUID>): List<Service> {
        val found = serviceRepository.findAllByIds(serviceIds).associateBy { it.id }
        serviceIds.firstOrNull { it !in found }?.let { throw ServiceNotFoundException(it) }
        return serviceIds.map { found.getValue(it) }
    }

    private fun computeMissing(
        required: List<SupplyRequirement>,
        supplies: Map<UUID, Supply>,
    ): List<MissingSupply> = required.mapNotNull { req ->
        val supply = supplies[req.supplyId]
        val available = supply?.quantityInStock ?: 0
        if (available < req.quantity) {
            MissingSupply(req.supplyId, supply?.name ?: "?", req.quantity, available)
        } else {
            null
        }
    }

    private fun cancelWithSuppliesUnavailable(execution: Execution, missing: List<MissingSupply>) {
        val canceled = execution.cancel()
        val payload = mapper.createObjectNode()
        payload.put("orderId", execution.orderId.toString())
        val arr = payload.putArray("missingSupplies")
        missing.forEach { m ->
            arr.addObject().apply {
                put("supplyId", m.supplyId.toString())
                put("name", m.name)
                put("requested", m.requested)
                put("available", m.available)
            }
        }
        val event = EventEnvelope(eventType = DomainEventType.SUPPLIES_UNAVAILABLE, payload = payload)

        writer.writeAll(
            puts = listOf(
                TxPut(
                    item = executionRepository.putItem(canceled),
                    conditionExpression = "#st = :awaiting",
                    expressionAttributeValues = mapOf(
                        ":awaiting" to AttributeValue.S(ExecutionStatus.AWAITING_DIAGNOSIS.name),
                    ),
                    expressionAttributeNames = mapOf("#st" to "status"),
                ),
                TxPut(outbox.putItem(event)),
            ),
        )
        metrics.suppliesUnavailable()
        metrics.executionStatusChanged(ExecutionStatus.CANCELED.name)
        logger.info(
            "SuppliesUnavailable emitted for order {} ({} missing), execution canceled",
            execution.orderId,
            missing.size,
        )
    }

    private fun buildDiagnoseFinishedPayload(
        execution: Execution,
        reservationId: UUID,
        diagnosedBy: User,
        services: List<Service>,
        required: List<SupplyRequirement>,
        supplies: Map<UUID, Supply>,
    ): JsonNode {
        val order = OrderCreatedPayload.from(execution.orderSnapshot)
        val node = mapper.createObjectNode()
        node.put("orderId", execution.orderId.toString())
        node.put("reservationId", reservationId.toString())
        node.putObject("diagnosedBy").apply {
            put("id", diagnosedBy.id.toString())
            put("document", diagnosedBy.document)
        }
        node.putObject("customer").apply {
            put("name", order.customer.name)
            put("email", order.customer.email)
        }

        var totalAmount = BigDecimal.ZERO

        val servicesArr = node.putArray("services")
        services.forEach { service ->
            totalAmount = totalAmount + service.price
            servicesArr.addObject().apply {
                put("id", service.id.toString())
                put("name", service.name)
                put("price", service.price)
            }
        }

        val suppliesArr = node.putArray("supplies")
        required.forEach { line ->
            val supply = supplies.getValue(line.supplyId)
            totalAmount = totalAmount + supply.price.multiply(BigDecimal(line.quantity))
            suppliesArr.addObject().apply {
                put("id", line.supplyId.toString())
                put("name", supply.name)
                put("quantity", line.quantity)
                put("unitPrice", supply.price)
            }
        }

        node.put("totalAmount", totalAmount)
        return node
    }
}
