package br.com.soat.messaging

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sns.SnsClient
import aws.sdk.kotlin.services.sns.model.MessageAttributeValue
import aws.sdk.kotlin.services.sns.model.PublishRequest
import aws.smithy.kotlin.runtime.net.url.Url
import kotlinx.coroutines.runBlocking

class SnsPublisher(
    private val topicArn: String,
    region: String = "us-east-1",
    endpointOverride: String? = null,
    accessKeyId: String? = null,
    secretAccessKey: String? = null,
) : AutoCloseable {

    private val client: SnsClient = runBlocking {
        SnsClient {
            this.region = region
            if (endpointOverride != null) this.endpointUrl = Url.parse(endpointOverride)
            if (accessKeyId != null && secretAccessKey != null) {
                this.credentialsProvider = StaticCredentialsProvider {
                    this.accessKeyId = accessKeyId
                    this.secretAccessKey = secretAccessKey
                }
            }
        }
    }

    fun publish(body: String, eventType: String, traceparent: String? = null) {
        runBlocking {
            client.publish(
                PublishRequest {
                    topicArn = this@SnsPublisher.topicArn
                    message = body
                    messageAttributes = buildMap {
                        put("eventType", MessageAttributeValue { dataType = "String"; stringValue = eventType })
                        if (traceparent != null) {
                            put("traceparent", MessageAttributeValue { dataType = "String"; stringValue = traceparent })
                        }
                    }
                },
            )
        }
    }

    override fun close() = client.close()
}
