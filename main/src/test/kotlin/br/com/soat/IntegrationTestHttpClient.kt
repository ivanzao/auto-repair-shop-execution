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

    fun createService(
        name: String,
        price: BigDecimal,
        headers: Map<String, String>,
    ): ObjectNode {
        val body = mapper.createObjectNode().apply {
            put("name", name)
            put("description", "servico de teste")
            put("price", price)
            putArray("requiredSupplies")
        }
        val response = post("/v1/services", mapper.writeValueAsString(body), headers)
        check(response.statusCode() == 201) { "createService failed: ${response.statusCode()} ${response.body()}" }
        return mapper.readTree(response.body()) as ObjectNode
    }

    fun listOrders(status: String, headers: Map<String, String>): HttpResponse<String> =
        get("/v1/orders?status=$status", headers)

    fun getOrder(orderId: String, headers: Map<String, String>): HttpResponse<String> =
        get("/v1/orders/$orderId", headers)

    fun finishDiagnosis(
        orderId: String,
        serviceIds: List<String>,
        supplies: List<Pair<String, Int>>,
        headers: Map<String, String>,
    ): HttpResponse<String> {
        val body = mapper.createObjectNode()
        val servicesArr = body.putArray("services")
        serviceIds.forEach { servicesArr.add(it) }
        val suppliesArr = body.putArray("supplies")
        supplies.forEach { (id, quantity) ->
            suppliesArr.addObject().put("id", id).put("quantity", quantity)
        }
        return post("/v1/orders/$orderId/finish-diagnosis", mapper.writeValueAsString(body), headers)
    }

    fun startOrder(orderId: String, headers: Map<String, String>): HttpResponse<String> =
        post("/v1/orders/$orderId/start", "{}", headers)

    fun finish(orderId: String, headers: Map<String, String>): HttpResponse<String> =
        post("/v1/orders/$orderId/finish", "{}", headers)

    fun fail(orderId: String, reason: String, headers: Map<String, String>): HttpResponse<String> =
        post("/v1/orders/$orderId/fail", """{"reason":"$reason"}""", headers)

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
