package br.com.soat

import br.com.soat.config.Config
import br.com.soat.config.fromClasspath
import br.com.soat.config.prometheusMeterRegistry
import br.com.soat.consumer.SagaDispatcher
import br.com.soat.consumer.SqsConsumerWorker
import br.com.soat.event.OutboxRepository
import br.com.soat.event.ProcessedEventRepository
import br.com.soat.event.SagaEventHandler
import br.com.soat.execution.ConfirmPaymentUseCase
import br.com.soat.execution.ExecutionLifecycleUseCase
import br.com.soat.execution.ExpireReservationsUseCase
import br.com.soat.execution.ReleaseReservationUseCase
import br.com.soat.execution.ReserveSuppliesUseCase
import br.com.soat.execution.TransactionalWriter
import br.com.soat.execution.handler.OrderCreatedHandler
import br.com.soat.execution.handler.PaymentConfirmedHandler
import br.com.soat.execution.handler.PaymentFailedHandler
import br.com.soat.execution.handler.QuoteRejectedHandler
import br.com.soat.execution.repository.ExecutionRepository
import br.com.soat.execution.ExecutionDynamoRepository
import br.com.soat.event.OutboxDynamoRepository
import br.com.soat.event.ProcessedEventDynamoRepository
import br.com.soat.metric.MetricsPort
import br.com.soat.metric.MicrometerMetricsPort
import br.com.soat.messaging.SnsPublisher
import br.com.soat.reservation.ReservationDynamoRepository
import br.com.soat.reservation.repository.ReservationRepository
import br.com.soat.scheduler.ScheduledTask
import br.com.soat.scheduler.ScheduledTaskRunner
import br.com.soat.scheduler.task.OutboxRelayTask
import br.com.soat.scheduler.task.ReservationExpiredTask
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.DynamoTransactionalWriter
import br.com.soat.supply.SupplyDynamoRepository
import br.com.soat.supply.SupplyUseCase
import br.com.soat.supply.repository.SupplyRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.koin.core.context.startKoin
import org.koin.dsl.bind
import org.koin.dsl.module
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("br.com.soat.MainKt")

fun main() {
    logger.info("Starting auto-repair-shop-execution application")
    val koinApplication = startKoin { modules(applicationModule) }
    val koin = koinApplication.koin
    val config = koin.get<Config>()

    koin.get<SqsConsumerWorker>().start()
    koin.get<ScheduledTaskRunner>().start()

    KtorHttpServer(koin = koin, port = config.getInt("server.port"), wait = true).start()
}

val applicationModule = module {
    single<Config> { Config.fromClasspath("application.yaml") }
    single<CoroutineDispatcher> { Dispatchers.IO }
    single<ObjectMapper> { jacksonObjectMapper().registerModule(JavaTimeModule()) }

    // observability
    single<PrometheusMeterRegistry> { prometheusMeterRegistry() }
    single<MeterRegistry> { get<PrometheusMeterRegistry>() }
    single<MetricsPort> { MicrometerMetricsPort(get()) }

    // dynamodb + sns
    single {
        val c = get<Config>()
        DynamoDb.create(
            tableName = c.getString("dynamodb.table.name"),
            region = c.getStringOrNull("aws.region") ?: "us-east-1",
            endpointOverride = c.getStringOrNull("aws.endpoint"),
            accessKeyId = c.getStringOrNull("aws.accessKeyId"),
            secretAccessKey = c.getStringOrNull("aws.secretAccessKey"),
        )
    }
    single {
        val c = get<Config>()
        SnsPublisher(
            topicArn = c.getString("sns.topic.arn"),
            region = c.getStringOrNull("aws.region") ?: "us-east-1",
            endpointOverride = c.getStringOrNull("aws.endpoint"),
            accessKeyId = c.getStringOrNull("aws.accessKeyId"),
            secretAccessKey = c.getStringOrNull("aws.secretAccessKey"),
        )
    }

    // storage
    single<SupplyRepository> { SupplyDynamoRepository(get()) }
    single<ExecutionRepository> { ExecutionDynamoRepository(get(), get()) }
    single<ReservationRepository> { ReservationDynamoRepository(get()) }
    single<OutboxRepository> { OutboxDynamoRepository(get(), get()) }
    single<ProcessedEventRepository> { ProcessedEventDynamoRepository(get()) }
    single<TransactionalWriter> { DynamoTransactionalWriter(get()) }

    // domain use cases
    single { SupplyUseCase(get()) }
    single { ReserveSuppliesUseCase(get(), get(), get(), get(), get(), get(), get()) }
    single { ReleaseReservationUseCase(get(), get(), get(), get()) }
    single { ConfirmPaymentUseCase(get(), get(), get(), get()) }
    single { ExecutionLifecycleUseCase(get(), get(), get(), get()) }
    single { ExpireReservationsUseCase(get(), get(), get()) }

    // saga handlers
    single { OrderCreatedHandler(get()) } bind SagaEventHandler::class
    single { PaymentConfirmedHandler(get()) } bind SagaEventHandler::class
    single { QuoteRejectedHandler(get()) } bind SagaEventHandler::class
    single { PaymentFailedHandler(get()) } bind SagaEventHandler::class
    single { SagaDispatcher(getAll(), get()) }

    // sqs consumer
    single {
        val c = get<Config>()
        SqsConsumerWorker(
            queueUrl = c.getString("sqs.queue.url"),
            region = c.getStringOrNull("aws.region") ?: "us-east-1",
            endpointOverride = c.getStringOrNull("aws.endpoint"),
            accessKeyId = c.getStringOrNull("aws.accessKeyId"),
            secretAccessKey = c.getStringOrNull("aws.secretAccessKey"),
            dispatcher = get(),
            mapper = get(),
            coroutineDispatcher = get(),
        )
    }

    // scheduled tasks
    single { OutboxRelayTask(get(), get(), get()) } bind ScheduledTask::class
    single {
        val c = get<Config>()
        ReservationExpiredTask(get(), c.getInt("reservation.job.interval.seconds", 3600).toLong())
    } bind ScheduledTask::class
    single { ScheduledTaskRunner(getAll()) }
}
