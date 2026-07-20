package br.com.soat.event

import br.com.soat.event.model.EventEnvelope
import br.com.soat.event.model.SagaEventType
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.DynamoTestSupport
import br.com.soat.storage.createExecutionTable
import br.com.soat.storage.dropExecutionTable
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxDynamoRepositoryIntegrationTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var db: DynamoDb
    private lateinit var repo: OutboxDynamoRepository

    @BeforeAll
    fun setup() {
        db = DynamoTestSupport.newDynamoDb("auto-repair-shop-execution-test")
        repo = OutboxDynamoRepository(db, mapper)
    }

    @AfterAll
    fun teardown() = db.close()

    @BeforeEach
    fun recreateTable() {
        db.dropExecutionTable()
        db.createExecutionTable()
    }

    private fun envelope() = EventEnvelope(
        eventType = SagaEventType.SUPPLIES_RESERVED,
        payload = mapper.createObjectNode().put("orderId", "abc"),
    )

    @Test
    fun `save then pending returns the envelope`() {
        val env = envelope()
        repo.save(env)
        val pending = repo.pending(10)
        assertEquals(1, pending.size)
        assertEquals(env.eventId, pending.single().eventId)
        assertEquals(SagaEventType.SUPPLIES_RESERVED, pending.single().eventType)
        assertEquals("abc", pending.single().payload["orderId"].asText())
    }

    @Test
    fun `markPublished removes from pending`() {
        val env = envelope()
        repo.save(env)
        repo.markPublished(env.eventId)
        assertTrue(repo.pending(10).isEmpty())
    }
}
