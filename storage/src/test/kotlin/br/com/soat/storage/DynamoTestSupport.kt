package br.com.soat.storage

import aws.sdk.kotlin.services.dynamodb.model.AttributeDefinition
import aws.sdk.kotlin.services.dynamodb.model.BillingMode
import aws.sdk.kotlin.services.dynamodb.model.CreateTableRequest
import aws.sdk.kotlin.services.dynamodb.model.DeleteTableRequest
import aws.sdk.kotlin.services.dynamodb.model.GlobalSecondaryIndex
import aws.sdk.kotlin.services.dynamodb.model.KeySchemaElement
import aws.sdk.kotlin.services.dynamodb.model.KeyType
import aws.sdk.kotlin.services.dynamodb.model.Projection
import aws.sdk.kotlin.services.dynamodb.model.ProjectionType
import aws.sdk.kotlin.services.dynamodb.model.ResourceNotFoundException
import aws.sdk.kotlin.services.dynamodb.model.ScalarAttributeType
import kotlinx.coroutines.runBlocking
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB
import org.testcontainers.utility.DockerImageName

fun DynamoDb.createExecutionTable() = runBlocking {
    client.createTable(
        CreateTableRequest {
            tableName = this@createExecutionTable.tableName
            billingMode = BillingMode.PayPerRequest
            attributeDefinitions = listOf(
                AttributeDefinition { attributeName = "pk"; attributeType = ScalarAttributeType.S },
                AttributeDefinition { attributeName = "sk"; attributeType = ScalarAttributeType.S },
                AttributeDefinition { attributeName = "gsi1pk"; attributeType = ScalarAttributeType.S },
                AttributeDefinition { attributeName = "gsi1sk"; attributeType = ScalarAttributeType.S },
            )
            keySchema = listOf(
                KeySchemaElement { attributeName = "pk"; keyType = KeyType.Hash },
                KeySchemaElement { attributeName = "sk"; keyType = KeyType.Range },
            )
            globalSecondaryIndexes = listOf(
                GlobalSecondaryIndex {
                    indexName = "gsi1"
                    keySchema = listOf(
                        KeySchemaElement { attributeName = "gsi1pk"; keyType = KeyType.Hash },
                        KeySchemaElement { attributeName = "gsi1sk"; keyType = KeyType.Range },
                    )
                    projection = Projection { projectionType = ProjectionType.All }
                },
            )
        },
    )
}

fun DynamoDb.dropExecutionTable() = runBlocking {
    try {
        client.deleteTable(DeleteTableRequest { tableName = this@dropExecutionTable.tableName })
    } catch (_: ResourceNotFoundException) {
    }
}

object DynamoTestSupport {
    val container: LocalStackContainer by lazy {
        LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
            .withServices(DYNAMODB)
            .withReuse(true)
            .also { it.start() }
    }

    fun newDynamoDb(tableName: String): DynamoDb {
        val endpoint = container.getEndpointOverride(DYNAMODB).toString()
        return DynamoDb.create(
            tableName = tableName,
            region = container.region,
            endpointOverride = endpoint,
            accessKeyId = container.accessKey,
            secretAccessKey = container.secretKey,
        )
    }
}
