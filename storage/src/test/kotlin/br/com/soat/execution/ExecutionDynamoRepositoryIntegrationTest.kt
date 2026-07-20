package br.com.soat.execution

import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.DynamoTestSupport
import br.com.soat.storage.createExecutionTable
import br.com.soat.storage.dropExecutionTable
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ExecutionDynamoRepositoryIntegrationTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var db: DynamoDb
    private lateinit var repo: ExecutionDynamoRepository

    @BeforeAll
    fun setup() {
        db = DynamoTestSupport.newDynamoDb("auto-repair-shop-execution-test")
        repo = ExecutionDynamoRepository(db, mapper)
    }

    @AfterAll
    fun teardown() = db.close()

    @BeforeEach
    fun recreateTable() {
        db.dropExecutionTable()
        db.createExecutionTable()
    }

    private fun execution(orderId: UUID = UUID.randomUUID(), status: ExecutionStatus = ExecutionStatus.RESERVED) =
        Execution(
            orderId = orderId,
            status = status,
            reservationId = UUID.randomUUID(),
            orderSnapshot = mapper.createObjectNode().put("orderId", orderId.toString()),
            paymentId = null,
        )

    @Test
    fun `save then findByOrderId returns equal`() {
        val e = execution()
        repo.save(e)
        val found = repo.findByOrderId(e.orderId)
        assertEquals(e.orderId, found?.orderId)
        assertEquals(ExecutionStatus.RESERVED, found?.status)
        assertEquals(e.reservationId, found?.reservationId)
        assertEquals(e.orderId.toString(), found?.orderSnapshot?.get("orderId")?.asText())
    }

    @Test
    fun `findByStatus queries the gsi`() {
        val queued = execution(status = ExecutionStatus.QUEUED)
        repo.save(queued)
        repo.save(execution(status = ExecutionStatus.RESERVED))

        val found = repo.findByStatus(ExecutionStatus.QUEUED)
        assertEquals(1, found.size)
        assertEquals(queued.orderId, found.single().orderId)
    }
}
