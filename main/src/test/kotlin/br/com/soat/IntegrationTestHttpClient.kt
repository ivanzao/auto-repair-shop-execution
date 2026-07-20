package br.com.soat

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class IntegrationTestHttpClient(private val serverPort: Int) {

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build()

    val mapper: ObjectMapper = jacksonObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

    fun createSupply(
        name: String,
        quantityInStock: Int,
        price: BigDecimal,
        headers: Map<String, String>,
    ): ObjectNode {
        val body = mapper.createObjectNode().apply {
            put("name", name)
            put("description", "peça de teste")
            put("quantityInStock", quantityInStock)
            put("price", price)
        }
        val response = post("/v1/supplies", mapper.writeValueAsString(body), headers)
        check(response.statusCode() == 201) { "createSupply failed: ${response.statusCode()} ${response.body()}" }
        return mapper.readTree(response.body()) as ObjectNode
    }

    fun listExecutions(status: String, headers: Map<String, String>): HttpResponse<String> =
        get("/v1/executions?status=$status", headers)

    fun getExecution(orderId: String, headers: Map<String, String>): HttpResponse<String> =
        get("/v1/executions/$orderId", headers)

    fun finishDiagnosis(orderId: String, headers: Map<String, String>): HttpResponse<String> =
        post("/v1/executions/$orderId/finish-diagnosis", "{}", headers)

    fun finish(orderId: String, headers: Map<String, String>): HttpResponse<String> =
        post("/v1/executions/$orderId/finish", "{}", headers)

    fun fail(orderId: String, reason: String, headers: Map<String, String>): HttpResponse<String> =
        post("/v1/executions/$orderId/fail", """{"reason":"$reason"}""", headers)

    fun post(path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort$path"))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    fun get(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort$path"))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    fun put(path: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort$path"))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    }

    fun delete(path: String, headers: Map<String, String> = emptyMap()): HttpResponse<String> {
        val builder = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort$path"))
            .header("Content-Type", "application/json")
        headers.forEach { (k, v) -> builder.header(k, v) }
        return client.send(builder.DELETE().build(), HttpResponse.BodyHandlers.ofString())
    }
}
