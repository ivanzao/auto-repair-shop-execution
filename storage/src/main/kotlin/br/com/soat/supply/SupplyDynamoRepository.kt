package br.com.soat.supply

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.decimal
import br.com.soat.storage.int
import br.com.soat.storage.n
import br.com.soat.storage.s
import br.com.soat.storage.str
import br.com.soat.storage.strOrNull
import br.com.soat.supply.model.Supply
import br.com.soat.supply.repository.SupplyRepository
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking

class SupplyDynamoRepository(private val db: DynamoDb) : SupplyRepository {

    private fun Supply.toItem(): Map<String, AttributeValue> = buildMap {
        put("pk", s(Keys.supply(id)))
        put("sk", s(Keys.supply(id)))
        put("gsi1pk", s(Keys.SUPPLY_LIST))
        put("gsi1sk", s(name))
        put("type", s("SUPPLY"))
        put("id", s(id.toString()))
        put("name", s(name))
        description?.let { put("description", s(it)) }
        put("quantityInStock", n(quantityInStock))
        put("price", n(price))
        put("version", n(version))
        put("createdAt", s(createdAt.toString()))
        put("modifiedAt", s(modifiedAt.toString()))
    }

    private fun Map<String, AttributeValue>.toSupply() = Supply(
        id = UUID.fromString(str("id")),
        createdAt = LocalDateTime.parse(str("createdAt")),
        modifiedAt = LocalDateTime.parse(str("modifiedAt")),
        version = int("version"),
        name = str("name"),
        description = strOrNull("description"),
        quantityInStock = int("quantityInStock"),
        price = decimal("price"),
    )

    override fun findById(id: UUID): Supply? = runBlocking {
        db.client.getItem(
            GetItemRequest {
                tableName = db.tableName
                key = mapOf("pk" to s(Keys.supply(id)), "sk" to s(Keys.supply(id)))
            },
        ).item?.toSupply()
    }

    override fun findAll(): List<Supply> = runBlocking {
        db.client.query(
            QueryRequest {
                tableName = db.tableName
                indexName = Keys.GSI
                keyConditionExpression = "gsi1pk = :pk"
                expressionAttributeValues = mapOf(":pk" to s(Keys.SUPPLY_LIST))
            },
        ).items.orEmpty().map { it.toSupply() }
    }

    override fun findAllByIds(ids: List<UUID>): List<Supply> = ids.mapNotNull { findById(it) }

    override fun create(supply: Supply): Supply = runBlocking {
        db.client.putItem(
            PutItemRequest {
                tableName = db.tableName
                item = supply.toItem()
                conditionExpression = "attribute_not_exists(pk)"
            },
        )
        supply
    }

    override fun update(supply: Supply): Supply = runBlocking {
        val updated = supply.copy(version = supply.version + 1, modifiedAt = LocalDateTime.now())
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
                key = mapOf("pk" to s(Keys.supply(id)), "sk" to s(Keys.supply(id)))
            },
        )
        Unit
    }
}
