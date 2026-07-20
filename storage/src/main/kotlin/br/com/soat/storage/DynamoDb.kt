package br.com.soat.storage

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.dynamodb.DynamoDbClient
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking

class DynamoDb(
    val client: DynamoDbClient,
    val tableName: String,
) : AutoCloseable {
    override fun close() = client.close()

    companion object {
        fun create(
            tableName: String,
            region: String = "us-east-1",
            endpointOverride: String? = null,
            accessKeyId: String? = null,
            secretAccessKey: String? = null,
        ): DynamoDb = runBlocking {
            val client = DynamoDbClient {
                this.region = region
                if (endpointOverride != null) this.endpointUrl = Url.parse(endpointOverride)
                if (accessKeyId != null && secretAccessKey != null) {
                    this.credentialsProvider = StaticCredentialsProvider {
                        this.accessKeyId = accessKeyId
                        this.secretAccessKey = secretAccessKey
                    }
                }
            }
            DynamoDb(client, tableName)
        }
    }
}
