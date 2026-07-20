package br.com.soat.reservation

import br.com.soat.reservation.model.Reservation
import br.com.soat.reservation.model.ReservationLine
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.DynamoTestSupport
import br.com.soat.storage.createExecutionTable
import br.com.soat.storage.dropExecutionTable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReservationDynamoRepositoryIntegrationTest {

    private lateinit var db: DynamoDb
    private lateinit var repo: ReservationDynamoRepository

    @BeforeAll
    fun setup() {
        db = DynamoTestSupport.newDynamoDb("auto-repair-shop-execution-test")
        repo = ReservationDynamoRepository(db)
    }

    @AfterAll
    fun teardown() = db.close()

    @BeforeEach
    fun recreateTable() {
        db.dropExecutionTable()
        db.createExecutionTable()
    }

    private fun reservation(expiresAt: Instant) = Reservation(
        orderId = UUID.randomUUID(),
        lines = listOf(ReservationLine(UUID.randomUUID(), 2)),
        expiresAt = expiresAt,
    )

    @Test
    fun `save then findById returns equal with lines`() {
        val r = reservation(Instant.now().plus(7, ChronoUnit.DAYS))
        repo.save(r)
        val found = repo.findById(r.id)
        assertEquals(r.id, found?.id)
        assertEquals(1, found?.lines?.size)
        assertEquals(2, found?.lines?.single()?.quantity)
        assertEquals(r.lines.single().supplyId, found?.lines?.single()?.supplyId)
    }

    @Test
    fun `findActiveExpiredBefore finds past-due active reservations`() {
        val expired = reservation(Instant.now().minus(1, ChronoUnit.HOURS))
        repo.save(expired)
        val future = reservation(Instant.now().plus(1, ChronoUnit.DAYS))
        repo.save(future)

        val found = repo.findActiveExpiredBefore(Instant.now(), 25)
        assertEquals(1, found.size)
        assertEquals(expired.id, found.single().id)
    }

    @Test
    fun `deactivated reservation leaves the active index`() {
        val r = reservation(Instant.now().minus(1, ChronoUnit.HOURS))
        repo.save(r)
        repo.save(r.release())

        assertTrue(repo.findActiveExpiredBefore(Instant.now(), 25).isEmpty())
        assertEquals(br.com.soat.reservation.model.ReservationStatus.RELEASED, repo.findById(r.id)?.status)
    }
}
