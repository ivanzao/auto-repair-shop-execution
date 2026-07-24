package br.com.soat

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.sns.SnsClient as AwsSnsClient
import aws.sdk.kotlin.services.sns.model.CreateTopicRequest
import aws.sdk.kotlin.services.sns.model.SubscribeRequest
import aws.sdk.kotlin.services.sqs.SqsClient
import aws.sdk.kotlin.services.sqs.model.CreateQueueRequest
import aws.sdk.kotlin.services.sqs.model.DeleteMessageRequest
import aws.sdk.kotlin.services.sqs.model.GetQueueAttributesRequest
import aws.sdk.kotlin.services.sqs.model.PurgeQueueRequest
import aws.sdk.kotlin.services.sqs.model.QueueAttributeName
import aws.sdk.kotlin.services.sqs.model.ReceiveMessageRequest
import aws.sdk.kotlin.services.sqs.model.SendMessageRequest
import aws.smithy.kotlin.runtime.net.url.Url
import br.com.soat.config.Config
import br.com.soat.config.fromClasspath
import br.com.soat.scheduler.ScheduledTaskRunner
import br.com.soat.consumer.SqsConsumerWorker
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
import br.com.soat.storage.DynamoDb
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.net.ServerSocket
import java.time.Instant
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB
import org.testcontainers.containers.localstack.LocalStackContainer.Service.SNS
import org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS
import org.testcontainers.utility.DockerImageName

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class IntegrationTest {

    protected val serverPort = ServerSocket(0).use { it.localPort }
    protected val localstack = LocalStackContainer(DockerImageName.parse("localstack/localstack:3.7"))
        .withServices(DYNAMODB, SNS, SQS)
        .withReuse(true)

    lateinit var koinApplication: KoinApplication
    lateinit var server: KtorHttpServer
    lateinit var http: IntegrationTestHttpClient
    lateinit var db: DynamoDb

    private lateinit var awsSqs: SqsClient
    private lateinit var inboundQueueUrl: String
    private lateinit var spyQueueUrl: String
    private val tableName = "auto-repair-shop-execution-test"

    @BeforeAll
    fun setup() {
        localstack.start()
        val endpoint = localstack.getEndpointOverride(DYNAMODB).toString()

        db = DynamoDb.create(
            tableName = tableName,
            region = localstack.region,
            endpointOverride = endpoint,
            accessKeyId = localstack.accessKey,
            secretAccessKey = localstack.secretKey,
        )
        createTable()

        val (topicArn, inboundUrl, spyUrl) = provisionMessaging(endpoint)
        inboundQueueUrl = inboundUrl
        spyQueueUrl = spyUrl

        awsSqs = runBlocking {
            SqsClient {
                this.region = localstack.region
                this.endpointUrl = Url.parse(endpoint)
                this.credentialsProvider = StaticCredentialsProvider {
                    this.accessKeyId = localstack.accessKey
                    this.secretAccessKey = localstack.secretKey
                }
            }
        }

        val config = Config.fromClasspath("application-test.yaml").apply {
            put("server.port", serverPort)
            put("dynamodb.table.name", tableName)
            put("sns.topic.arn", topicArn)
            put("sqs.queue.url", inboundQueueUrl)
            put("aws.endpoint", endpoint)
            put("aws.region", localstack.region)
            put("aws.accessKeyId", localstack.accessKey)
            put("aws.secretAccessKey", localstack.secretKey)
        }

        koinApplication = startKoin { modules(applicationModule, module { single<Config> { config } }) }
        http = IntegrationTestHttpClient(serverPort)

        get<SqsConsumerWorker>().start()
        get<ScheduledTaskRunner>().start()

        server = KtorHttpServer(koin = koinApplication.koin, port = serverPort, wait = false)
        server.start()
    }

    @BeforeEach
    fun purgeQueues() {
        runBlocking {
            awsSqs.purgeQueue(PurgeQueueRequest { queueUrl = inboundQueueUrl })
            awsSqs.purgeQueue(PurgeQueueRequest { queueUrl = spyQueueUrl })
        }
    }

    @AfterAll
    fun tearDown() {
        server.stop()
        get<ScheduledTaskRunner>().stop()
        dropTable()
        db.close()
        awsSqs.close()
        stopKoin()
    }

    private fun createTable() = runBlocking {
        db.client.createTable(CreateTableRequest {
            tableName = this@IntegrationTest.tableName
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
        })
        Unit
    }

    private fun dropTable() = runBlocking {
        try {
            db.client.deleteTable(DeleteTableRequest { tableName = this@IntegrationTest.tableName })
        } catch (_: ResourceNotFoundException) {
        }
        Unit
    }

    private fun provisionMessaging(endpoint: String): Triple<String, String, String> = runBlocking {
        val sns = AwsSnsClient {
            this.region = localstack.region
            this.endpointUrl = Url.parse(endpoint)
            this.credentialsProvider = StaticCredentialsProvider {
                this.accessKeyId = localstack.accessKey
                this.secretAccessKey = localstack.secretKey
            }
        }
        val sqs = SqsClient {
            this.region = localstack.region
            this.endpointUrl = Url.parse(endpoint)
            this.credentialsProvider = StaticCredentialsProvider {
                this.accessKeyId = localstack.accessKey
                this.secretAccessKey = localstack.secretKey
            }
        }

        val topicArn = sns.createTopic(CreateTopicRequest { name = "auto-repair-shop-execution-events-test" }).topicArn!!
        val inbound = sqs.createQueue(CreateQueueRequest { queueName = "auto-repair-shop-execution-saga-test" }).queueUrl!!
        val spy = sqs.createQueue(CreateQueueRequest { queueName = "auto-repair-shop-execution-spy-test" }).queueUrl!!

        val spyArn = sqs.getQueueAttributes(GetQueueAttributesRequest {
            queueUrl = spy
            attributeNames = listOf(QueueAttributeName.QueueArn)
        }).attributes!![QueueAttributeName.QueueArn]!!

        sns.subscribe(SubscribeRequest {
            this.topicArn = topicArn
            protocol = "sqs"
            this.endpoint = spyArn
            attributes = mapOf("RawMessageDelivery" to "true")
        })

        sns.close()
        sqs.close()
        Triple(topicArn, inbound, spy)
    }

    inline fun <reified T> get(): T = koinApplication.koin.get()

    protected fun sendToQueue(envelope: JsonNode) {
        val mapper = get<ObjectMapper>()
        runBlocking {
            awsSqs.sendMessage(SendMessageRequest {
                queueUrl = inboundQueueUrl
                messageBody = mapper.writeValueAsString(envelope)
            })
        }
    }

    protected fun waitForPublishedEvent(eventType: String, orderId: String? = null, timeoutSeconds: Long = 25): JsonNode {
        val mapper = get<ObjectMapper>()
        val deadline = Instant.now().plusSeconds(timeoutSeconds)
        while (Instant.now().isBefore(deadline)) {
            val messages = runBlocking {
                awsSqs.receiveMessage(ReceiveMessageRequest {
                    queueUrl = spyQueueUrl
                    maxNumberOfMessages = 10
                    waitTimeSeconds = 2
                    messageAttributeNames = listOf("All")
                }).messages.orEmpty()
            }
            for (msg in messages) {
                val envelope = mapper.readTree(msg.body)
                val matchesType = envelope["eventType"]?.asText() == eventType
                val matchesOrder = orderId == null || envelope["payload"]?.get("orderId")?.asText() == orderId
                if (matchesType && matchesOrder) {
                    runBlocking {
                        awsSqs.deleteMessage(DeleteMessageRequest { queueUrl = spyQueueUrl; receiptHandle = msg.receiptHandle })
                    }
                    return envelope["payload"]
                }
            }
        }
        throw AssertionError("Timeout waiting for published event eventType=$eventType orderId=$orderId")
    }

    protected fun adminHeaders(userId: UUID = UUID.randomUUID()): Map<String, String> =
        mapOf("Authorization" to "Bearer ${fakeJwt(userId, "ADMIN")}")

    protected fun mechanicHeaders(userId: UUID = UUID.randomUUID()): Map<String, String> =
        mapOf("Authorization" to "Bearer ${fakeJwt(userId, "MECHANIC")}")

    private fun fakeJwt(userId: UUID, role: String): String {
        val header = b64u("""{"alg":"none","typ":"JWT"}""")
        val payload = b64u("""{"sub":"$userId","role":"$role","exp":9999999999}""")
        return "$header.$payload.test"
    }

    private fun b64u(s: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
}
