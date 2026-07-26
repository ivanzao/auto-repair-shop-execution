package br.com.soat.service

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import br.com.soat.service.model.Service
import br.com.soat.service.repository.ServiceRepository
import br.com.soat.shared.model.SupplyRequirement
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.decimal
import br.com.soat.storage.int
import br.com.soat.storage.n
import br.com.soat.storage.s
import br.com.soat.storage.str
import br.com.soat.storage.strOrNull
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking

class ServiceDynamoRepository(
    private val db: DynamoDb,
    private val mapper: ObjectMapper,
) : ServiceRepository {

    private val requirementListType =
        mapper.typeFactory.constructCollectionType(List::class.java, SupplyRequirement::class.java)

    private fun Service.toItem(): Map<String, AttributeValue> = buildMap {
        put("pk", s(Keys.service(id)))
        put("sk", s(Keys.service(id)))
        put("gsi1pk", s(Keys.SERVICE_LIST))
        put("gsi1sk", s(name))
        put("type", s("SERVICE"))
        put("id", s(id.toString()))
        put("name", s(name))
        description?.let { put("description", s(it)) }
        put("price", n(price))
        put("requiredSupplies", s(mapper.writeValueAsString(requiredSupplies)))
        put("version", n(version))
        put("createdAt", s(createdAt.toString()))
        put("modifiedAt", s(modifiedAt.toString()))
    }

    private fun Map<String, AttributeValue>.toService(): Service {
        val requirements: List<SupplyRequirement> = mapper.readValue(str("requiredSupplies"), requirementListType)
        return Service(
            id = UUID.fromString(str("id")),
            createdAt = LocalDateTime.parse(str("createdAt")),
            modifiedAt = LocalDateTime.parse(str("modifiedAt")),
            version = int("version"),
            requiredSupplies = requirements,
            name = str("name"),
            description = strOrNull("description"),
            price = decimal("price"),
        )
    }

    override fun findById(id: UUID): Service? = runBlocking {
        db.client.getItem(
            GetItemRequest {
                tableName = db.tableName
                key = mapOf("pk" to s(Keys.service(id)), "sk" to s(Keys.service(id)))
            },
        ).item?.toService()
    }

    override fun findAll(): List<Service> = runBlocking {
        db.client.query(
            QueryRequest {
                tableName = db.tableName
                indexName = Keys.GSI
                keyConditionExpression = "gsi1pk = :pk"
                expressionAttributeValues = mapOf(":pk" to s(Keys.SERVICE_LIST))
            },
        ).items.orEmpty().map { it.toService() }
    }

    override fun findAllByIds(servicesIds: List<UUID>): List<Service> = servicesIds.mapNotNull { findById(it) }

    override fun create(service: Service): Service = runBlocking {
        db.client.putItem(
            PutItemRequest {
                tableName = db.tableName
                item = service.toItem()
                conditionExpression = "attribute_not_exists(pk)"
            },
        )
        service
    }

    override fun update(service: Service): Service = runBlocking {
        val updated = service.copy(version = service.version + 1, modifiedAt = LocalDateTime.now())
        db.client.putItem(
            PutItemRequest {
                tableName = db.tableName
                item = updated.toItem()
                conditionExpression = "attribute_exists(pk)"
            },
        )
        updated
    }

    override fun delete(id: UUID): Unit = runBlocking {
        db.client.deleteItem(
            DeleteItemRequest {
                tableName = db.tableName
                key = mapOf("pk" to s(Keys.service(id)), "sk" to s(Keys.service(id)))
            },
        )
        Unit
    }
}
