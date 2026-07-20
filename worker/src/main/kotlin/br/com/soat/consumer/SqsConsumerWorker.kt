package br.com.soat.consumer

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.smithy.kotlin.runtime.net.url.Url
import br.com.soat.event.model.EventEnvelope
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Long-poll da fila de saga. Parse do envelope cru → dispatch → deleteMessage só em sucesso.
 * Falha em processar deixa a mensagem para redelivery.
 */
class SqsConsumerWorker(
    private val queueUrl: String,
    private val region: String,
    private val endpointOverride: String?,
    private val accessKeyId: String?,
    private val secretAccessKey: String?,
    private val dispatcher: SagaDispatcher,
    private val mapper: ObjectMapper,
    private val coroutineDispatcher: CoroutineDispatcher,
) {
    private val logger = LoggerFactory.getLogger(SqsConsumerWorker::class.java)

    fun start() {
        val client = SqsClient {
            this.region = this@SqsConsumerWorker.region
            if (endpointOverride != null) this.endpointUrl = Url.parse(endpointOverride)
            if (accessKeyId != null && secretAccessKey != null) {
                this.credentialsProvider = StaticCredentialsProvider {
                    this.accessKeyId = this@SqsConsumerWorker.accessKeyId!!
                    this.secretAccessKey = this@SqsConsumerWorker.secretAccessKey!!
                }
            }
        }
        CoroutineScope(coroutineDispatcher).launch {
            logger.info("SqsConsumerWorker polling $queueUrl")
            while (isActive) {
                val messages = client.receiveMessage(
                    ReceiveMessageRequest {
                        this.queueUrl = this@SqsConsumerWorker.queueUrl
                        maxNumberOfMessages = 10
                        waitTimeSeconds = 10
                    },
                ).messages.orEmpty()
                for (msg in messages) {
                    try {
                        val env = mapper.readValue(msg.body!!, EventEnvelope::class.java)
                        dispatcher.dispatch(env)
                        client.deleteMessage(
                            DeleteMessageRequest {
                                this.queueUrl = this@SqsConsumerWorker.queueUrl
                                receiptHandle = msg.receiptHandle
                            },
                        )
                    } catch (e: Exception) {
                        logger.error("Failed to process SQS message ${msg.messageId}; leaving for redelivery", e)
                    }
                }
            }
        }
    }
}
