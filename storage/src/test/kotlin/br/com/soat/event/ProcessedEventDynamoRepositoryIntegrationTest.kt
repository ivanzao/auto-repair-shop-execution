package br.com.soat.event

import br.com.soat.storage.DynamoDb
import br.com.soat.storage.DynamoTestSupport
import br.com.soat.storage.createExecutionTable
import br.com.soat.storage.dropExecutionTable
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProcessedEventDynamoRepositoryIntegrationTest {

    private lateinit var db: DynamoDb
    private lateinit var repo: ProcessedEventDynamoRepository

    @BeforeAll
    fun setup() {
        db = DynamoTestSupport.newDynamoDb("auto-repair-shop-execution-test")
        repo = ProcessedEventDynamoRepository(db)
    }

    @AfterAll
    fun teardown() = db.close()

    @BeforeEach
    fun recreateTable() {
        db.dropExecutionTable()
        db.createExecutionTable()
    }

    @Test
    fun `markProcessed is idempotent per consumer`() {
        val eventId = UUID.randomUUID()
        assertTrue(repo.markProcessed(eventId, "H"))
        assertFalse(repo.markProcessed(eventId, "H"))
        assertTrue(repo.isProcessed(eventId, "H"))
    }

    @Test
    fun `different consumers dedup independently`() {
        val eventId = UUID.randomUUID()
        assertTrue(repo.markProcessed(eventId, "A"))
        assertTrue(repo.markProcessed(eventId, "B"))
        assertFalse(repo.isProcessed(eventId, "C"))
    }
}
