package br.com.soat.service

import br.com.soat.IntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ServiceIntegrationTest : IntegrationTest() {

    @Test
    fun `create then get service`() {
        val created = http.createService("Troca de oleo", "100.00".toBigDecimal(), adminHeaders())
        val id = created["id"].asText()

        val response = http.get("/v1/services/$id", adminHeaders())
        assertEquals(200, response.statusCode())
        val body = http.mapper.readTree(response.body())
        assertEquals("Troca de oleo", body["name"].asText())
        assertEquals(0, "100.00".toBigDecimal().compareTo(body["price"].decimalValue()))
    }

    @Test
    fun `list services`() {
        http.createService("Alinhamento", "80.00".toBigDecimal(), adminHeaders())
        http.createService("Balanceamento", "60.00".toBigDecimal(), adminHeaders())

        val response = http.get("/v1/services", adminHeaders())
        assertEquals(200, response.statusCode())
        assertTrue(http.mapper.readTree(response.body()).size() >= 2)
    }

    @Test
    fun `update and delete service`() {
        val created = http.createService("Temp", "10.00".toBigDecimal(), adminHeaders())
        val id = created["id"].asText()

        val updateBody = """{"name":"Temp2","description":"d","price":19.90,"requiredSupplies":[]}"""
        val updated = http.put("/v1/services/$id", updateBody, adminHeaders())
        assertEquals(200, updated.statusCode())
        assertEquals("Temp2", http.mapper.readTree(updated.body())["name"].asText())

        assertEquals(204, http.delete("/v1/services/$id", adminHeaders()).statusCode())
        assertEquals(404, http.get("/v1/services/$id", adminHeaders()).statusCode())
    }

    @Test
    fun `unknown service returns 404`() {
        assertEquals(404, http.get("/v1/services/${UUID.randomUUID()}", adminHeaders()).statusCode())
    }

    @Test
    fun `unauthenticated request is rejected`() {
        assertEquals(401, http.get("/v1/services").statusCode())
    }

    @Test
    fun `a token without cpf is rejected`() {
        val header = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"sub":"${UUID.randomUUID()}","role":"ADMIN","exp":9999999999}""".toByteArray())
        val headers = mapOf("Authorization" to "Bearer $header.$payload.test")

        assertEquals(401, http.get("/v1/services", headers).statusCode())
    }

    @Test
    fun `attendant and mechanic can read the catalog`() {
        assertEquals(200, http.get("/v1/services", attendantHeaders()).statusCode())
        assertEquals(200, http.get("/v1/services", mechanicHeaders()).statusCode())
    }

    @Test
    fun `attendant and mechanic can read the stock`() {
        assertEquals(200, http.get("/v1/supplies", attendantHeaders()).statusCode())
        assertEquals(200, http.get("/v1/supplies", mechanicHeaders()).statusCode())
    }
}
