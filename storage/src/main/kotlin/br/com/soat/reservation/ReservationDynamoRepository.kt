package br.com.soat.reservation

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import br.com.soat.reservation.model.Reservation
import br.com.soat.reservation.model.ReservationLine
import br.com.soat.reservation.model.ReservationStatus
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.n
import br.com.soat.storage.s
import br.com.soat.storage.str
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking

class ReservationDynamoRepository(private val db: DynamoDb) : ReservationRepository {

    override fun putItem(reservation: Reservation): Map<String, AttributeValue> = buildMap {
        put("pk", s(Keys.reservation(reservation.id)))
        put("sk", s(Keys.reservation(reservation.id)))
        // Esparso: apenas reserva ACTIVE participa do índice de expiração.
        if (reservation.status == ReservationStatus.ACTIVE) {
            put("gsi1pk", s(Keys.RES_ACTIVE))
            put("gsi1sk", s(reservation.expiresAt.toString()))
        }
        put("type", s("RESERVATION"))
        put("id", s(reservation.id.toString()))
        put("orderId", s(reservation.orderId.toString()))
        put("status", s(reservation.status.name))
        put("expiresAt", s(reservation.expiresAt.toString()))
        put("createdAt", s(reservation.createdAt.toString()))
        put(
            "lines",
            AttributeValue.L(
                reservation.lines.map { line ->
                    AttributeValue.M(
                        mapOf(
                            "supplyId" to s(line.supplyId.toString()),
                            "quantity" to n(line.quantity),
                        ),
                    )
                },
            ),
        )
    }

    private fun Map<String, AttributeValue>.toReservation(): Reservation {
        val lines = (this["lines"] as AttributeValue.L).value.map {
            val m = (it as AttributeValue.M).value
            ReservationLine(
                supplyId = UUID.fromString(m.str("supplyId")),
                quantity = (m["quantity"] as AttributeValue.N).value.toInt(),
            )
        }
        return Reservation(
            id = UUID.fromString(str("id")),
            orderId = UUID.fromString(str("orderId")),
            status = ReservationStatus.valueOf(str("status")),
            lines = lines,
            expiresAt = Instant.parse(str("expiresAt")),
            createdAt = Instant.parse(str("createdAt")),
        )
    }

    override fun save(reservation: Reservation): Unit = runBlocking {
        db.client.putItem(PutItemRequest { tableName = db.tableName; item = putItem(reservation) })
        Unit
    }

    override fun findById(id: UUID): Reservation? = runBlocking {
        db.client.getItem(
            GetItemRequest {
                tableName = db.tableName
                key = mapOf("pk" to s(Keys.reservation(id)), "sk" to s(Keys.reservation(id)))
            },
        ).item?.toReservation()
    }

    override fun findActiveExpiredBefore(now: Instant, limit: Int): List<Reservation> = runBlocking {
        db.client.query(
            QueryRequest {
                tableName = db.tableName
                indexName = Keys.GSI
                keyConditionExpression = "gsi1pk = :pk AND gsi1sk < :now"
                expressionAttributeValues = mapOf(
                    ":pk" to s(Keys.RES_ACTIVE),
                    ":now" to s(now.toString()),
                )
                this.limit = limit
            },
        ).items.orEmpty().map { it.toReservation() }
    }
}
