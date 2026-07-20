package br.com.soat.supply

import br.com.soat.storage.DynamoDb
import br.com.soat.storage.DynamoTestSupport
import br.com.soat.storage.createExecutionTable
import br.com.soat.storage.dropExecutionTable
import br.com.soat.supply.model.Supply
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SupplyDynamoRepositoryIntegrationTest {

    private lateinit var db: DynamoDb
    private lateinit var repo: SupplyDynamoRepository

    @BeforeAll
    fun setup() {
        db = DynamoTestSupport.newDynamoDb("auto-repair-shop-execution-test")
        repo = SupplyDynamoRepository(db)
    }

    @AfterAll
    fun teardown() {
        db.close()
    }

    @BeforeEach
    fun recreateTable() {
        db.dropExecutionTable()
        db.createExecutionTable()
    }

    private fun supply(name: String = "Filtro", stock: Int = 5) = Supply(
        name = name,
        description = "peça",
        quantityInStock = stock,
        price = "30.00".toBigDecimal(),
    )

    @Test
    fun `create then findById returns equal supply`() {
        val created = repo.create(supply())
        val found = repo.findById(created.id)
        assertEquals(created.id, found?.id)
        assertEquals("Filtro", found?.name)
        assertEquals(5, found?.quantityInStock)
        // DynamoDB trima zeros à direita em números; comparação numérica (compareTo), não por scale/equals.
        assertEquals(0, "30.00".toBigDecimal().compareTo(found?.price))
    }

    @Test
    fun `findAll lists created supplies`() {
        repo.create(supply(name = "A"))
        repo.create(supply(name = "B"))
        val all = repo.findAll()
        assertEquals(2, all.size)
        assertTrue(all.map { it.name }.containsAll(listOf("A", "B")))
    }

    @Test
    fun `findAllByIds returns only existing`() {
        val a = repo.create(supply(name = "A"))
        val missing = UUID.randomUUID()
        val found = repo.findAllByIds(listOf(a.id, missing))
        assertEquals(1, found.size)
        assertEquals(a.id, found.single().id)
    }

    @Test
    fun `update increments version`() {
        val created = repo.create(supply())
        val updated = repo.update(created.copy(quantityInStock = 9))
        assertEquals(created.version + 1, updated.version)
        assertEquals(9, repo.findById(created.id)?.quantityInStock)
    }

    @Test
    fun `delete removes supply`() {
        val created = repo.create(supply())
        repo.delete(created.id)
        assertNull(repo.findById(created.id))
    }
}
