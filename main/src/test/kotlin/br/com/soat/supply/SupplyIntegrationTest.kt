package br.com.soat.supply

import br.com.soat.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SupplyIntegrationTest : IntegrationTest() {

    @Test
    fun `create then get supply`() {
        val created = http.createSupply("Óleo 5W30", 10, "45.90".toBigDecimal(), adminHeaders())
        val id = created["id"].asText()

        val response = http.get("/v1/supplies/$id", adminHeaders())
        assertEquals(200, response.statusCode())
        val body = http.mapper.readTree(response.body())
        assertEquals("Óleo 5W30", body["name"].asText())
        assertEquals(10, body["quantityInStock"].asInt())
    }

    @Test
    fun `list supplies`() {
        http.createSupply("Item A", 1, "1.00".toBigDecimal(), adminHeaders())
        http.createSupply("Item B", 2, "2.00".toBigDecimal(), adminHeaders())

        val response = http.get("/v1/supplies", adminHeaders())
        assertEquals(200, response.statusCode())
        assertTrue(http.mapper.readTree(response.body()).size() >= 2)
    }

    @Test
    fun `update and delete supply`() {
        val created = http.createSupply("Temp", 3, "3.00".toBigDecimal(), adminHeaders())
        val id = created["id"].asText()

        val updateBody = """{"name":"Temp2","description":"d","quantityInStock":7,"price":9.90}"""
        val updated = http.put("/v1/supplies/$id", updateBody, adminHeaders())
        assertEquals(200, updated.statusCode())
        assertEquals(7, http.mapper.readTree(updated.body())["quantityInStock"].asInt())

        val deleted = http.delete("/v1/supplies/$id", adminHeaders())
        assertEquals(204, deleted.statusCode())
        assertEquals(404, http.get("/v1/supplies/$id", adminHeaders()).statusCode())
    }

    @Test
    fun `unauthenticated request is rejected`() {
        assertEquals(401, http.get("/v1/supplies").statusCode())
    }

    private fun assertTrue(condition: Boolean) = org.junit.jupiter.api.Assertions.assertTrue(condition)
}
