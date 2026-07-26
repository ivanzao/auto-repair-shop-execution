package br.com.soat.service

import br.com.soat.service.model.Service
import br.com.soat.shared.model.SupplyRequirement
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.DynamoTestSupport
import br.com.soat.storage.createExecutionTable
import br.com.soat.storage.dropExecutionTable
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
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
class ServiceDynamoRepositoryIntegrationTest {

    private val mapper: ObjectMapper = jacksonObjectMapper().registerModule(JavaTimeModule())
    private lateinit var db: DynamoDb
    private lateinit var repo: ServiceDynamoRepository

    @BeforeAll
    fun setup() {
        db = DynamoTestSupport.newDynamoDb("auto-repair-shop-execution-test")
        repo = ServiceDynamoRepository(db, mapper)
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

    private fun service(name: String = "Troca de oleo", price: String = "100.00") = Service(
        name = name,
        description = "servico",
        price = price.toBigDecimal(),
    )

    @Test
    fun `create then findById returns equal service`() {
        val created = repo.create(service())
        val found = repo.findById(created.id)
        assertEquals(created.id, found?.id)
        assertEquals("Troca de oleo", found?.name)
        assertEquals(0, "100.00".toBigDecimal().compareTo(found?.price))
    }

    @Test
    fun `requiredSupplies survive the round trip`() {
        val supplyId = UUID.randomUUID()
        val created = repo.create(service().copy(requiredSupplies = listOf(SupplyRequirement(supplyId, 3))))
        val found = repo.findById(created.id)!!
        assertEquals(supplyId, found.requiredSupplies.single().supplyId)
        assertEquals(3, found.requiredSupplies.single().quantity)
    }

    @Test
    fun `findAll lists created services`() {
        repo.create(service(name = "A"))
        repo.create(service(name = "B"))
        val all = repo.findAll()
        assertEquals(2, all.size)
        assertTrue(all.map { it.name }.containsAll(listOf("A", "B")))
    }

    @Test
    fun `findAllByIds returns only existing`() {
        val a = repo.create(service(name = "A"))
        val found = repo.findAllByIds(listOf(a.id, UUID.randomUUID()))
        assertEquals(1, found.size)
        assertEquals(a.id, found.single().id)
    }

    @Test
    fun `update increments version`() {
        val created = repo.create(service())
        val updated = repo.update(created.copy(price = "120.00".toBigDecimal()))
        assertEquals(created.version + 1, updated.version)
        assertEquals(0, "120.00".toBigDecimal().compareTo(repo.findById(created.id)?.price))
    }

    @Test
    fun `delete removes service`() {
        val created = repo.create(service())
        repo.delete(created.id)
        assertNull(repo.findById(created.id))
    }
}
