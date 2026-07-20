# Fase 4 — Plano 4: Serviço execution (auto-repair-shop-execution) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **Antes de tudo, leia a seção "Fonte da verdade"** — o código revisado de order/billing tem prioridade sobre este plano quando divergirem.

**Goal:** Construir do zero o microsserviço **execution** (Kotlin/Ktor, DynamoDB), que consome `OrderCreated`, reserva insumos de forma atômica, produz `SuppliesReserved` (repassando o quote priced) + os eventos de ciclo de execução + compensações, roda o job de `ReservationExpired`, e sobe em hml/prod via CI/CD + Kubernetes.

**Architecture:** Esqueleto hexagonal multi-módulo clonado do monólito `auto-repair-shop` (que vira o order), trocando o storage Postgres/Exposed por **DynamoDB single-table** e adicionando a metade consumidora da saga (worker SQS) que o monólito não tinha. Outbox transacional em DynamoDB → relay agendado → SNS; idempotência por `processed_events` + escritas condicionais. Coreografia pura, sem orquestrador.

**Tech Stack:** Kotlin 2.2.10, Ktor 3.3.3, Koin 4.1.1, AWS SDK Kotlin 1.5.13 (dynamodb + sns + sqs), Jackson 2.20.1, Micrometer/Prometheus, JUnit 5 + MockK + TestContainers/LocalStack, Gradle (version catalog), Docker, Kustomize, GitHub Actions.

## Fonte da verdade (LEIA ANTES DE QUALQUER TASK)

Este plano foi escrito em 2026-07-18 e **pode estar desatualizado**. Os serviços **order** (`../auto-repair-shop`) e **billing** (`../auto-repair-shop-billing`) já foram **implementados e revisados** — são a **fonte da verdade** para nomenclatura, estrutura de módulos/pacotes, organização de testes, convenções de código e o **contrato de eventos de fato**. Quando este plano divergir do código real desses repos, **priorize o código** (coisas podem ter mudado deliberadamente). **Ignore os markdowns de contrato/spec** (`saga-event-contract.md`, specs) quando conflitarem com o código implementado.

Regra prática: antes de cada task, olhe como order/billing fazem o equivalente (agregado de domínio, handler inbound, repositório, teste de integração, envelope, outbox, publisher/consumer) e **espelhe** — não reinvente nomes.

**Correções que sobrescrevem o corpo deste plano (aplicar em TODAS as ocorrências):**

| Este plano diz | Use isto (código real de order/billing) |
|---|---|
| "Renomear `Supply` → `Part`" | **NÃO renomear.** O domínio usa `Supply`/`supplies`/`SupplyRequirement`/`supplyId` (ver order). Agregado de estoque = `Supply`, rota `/v1/supplies`. Onde o plano escrever `Part`/`Parts`/`/v1/parts`, leia como `Supply`/`Supplies`/`/v1/supplies`. |
| `PartsReserved` | **`SuppliesReserved`** — confirmado; é o nome que o billing consome. |
| Nome dos demais eventos | **Casar exatamente com o consumidor real.** A nomenclatura no código é **mista** (ex.: order consome `PartsUnavailable`, não `SuppliesUnavailable`). Antes de nomear um evento produzido, confira `../auto-repair-shop/consumer/src/main/kotlin/br/com/soat/consumer/EventType.kt` e os handlers de order/billing e use a string que eles esperam. Não renomeie eventos em massa. |
| dedup `processed_events` / `ProcessedEvent` | Conceito **`idempotency` / `IdempotencyRepository`** (order/billing). No Dynamo é um item, mas mantenha esse nome. |
| construir `SagaOutbox`/`SqsSagaConsumer`/`SagaEnvelope`/`SagaEventType` do zero | **Espelhar os módulos reais de saga**: `producer` (outbox→SNS com `EventEnvelopeSerializer`/`SnsEventPublisher`/`OutboxRelay`) e `consumer` (`InboundEventConsumer` + `InboundEventHandler` em `consumer/handler/*` delegando a um `*ListenerUseCase` no domain). Só a camada de storage muda (Dynamo em vez de Postgres). Eventos de saída = subclasses de `DomainEvent` gravadas via `OutboxRepository.save()` e publicadas por `EventPublisher`. |
| métodos de use case tipo `on<Evento>` (ex. `onOrderCreated`) | **Verbos no imperativo que indicam a ação**, como no `OrderListenerUseCase` do order (`confirmPayment`, `finishExecution`, `cancel`) e no billing (`createQuote`, `handlePaymentUpdate`, `refundPayment`). Nada de `on<NomeDoEvento>`. |

**Contrato concreto com o billing (não inventar):** o `SuppliesReserved` que o execution produz é consumido pelo billing em `../auto-repair-shop-billing/consumer/src/main/kotlin/br/com/soat/consumer/handler/SuppliesReservedHandler.kt` (→ `RegisterQuoteRequest`). O payload **deve** casar exatamente:

```jsonc
{
  "orderId": "uuid",
  "reservationId": "uuid",
  "customer": { "name": "string", "email": "string" },
  "services": [ { "name": "string", "price": 149.90 } ],
  "supplies": [ { "id": "uuid", "name": "string", "quantity": 2, "unitPrice": 30.00 } ],
  "totalAmount": 209.90
}
```

**Infra = terraform, não markdown:** confira nomes reais em `../auto-repair-shop-infra/modules/messaging` (ex.: fila `auto-repair-shop-execution-queue-{env}`; SSM `sqs/execution-queue-url` **e** `sqs/execution-saga-queue-url` coexistem; tópico `auto-repair-shop-execution-events-{env}`, SSM `sns/execution-events-topic-arn`; attribute SNS `eventType` camelCase).

## Contexto de leitura obrigatória

- Contrato de eventos: `../auto-repair-shop-infra/docs/saga-event-contract.md` (envelope, catálogo, payloads de `OrderCreated`/`PartsReserved`, resumo do serviço execution).
- Spec governante: `../auto-repair-shop-infra/docs/superpowers/specs/2026-07-07-fase4-microsservicos-design.md`.
- Contrato de infra (nomes/SSM/node-ports fixados): `../auto-repair-shop-infra/docs/superpowers/plans/2026-07-07-fase4-plano-1-infra.md`, seção "Contrato produzido para os Planos 2–4".
- Repos modelo (revisados, **fonte da verdade** — ver seção acima): `../auto-repair-shop` (order) e `../auto-repair-shop-billing` (billing). Sempre que uma task disser "clonar de X", copiar e adaptar do código real desses repos; **não** reinventar. Os itens abaixo (contrato/spec em markdown) são **secundários**: se conflitarem com o código, o código vence.

## Global Constraints

- Pacote base `br.com.soat`; documentação e mensagens em **PT-BR**.
- **Terminologia `Supply`/`supplies`** (igual ao domínio do order: `SupplyRequirement`, `supplyId`, `supplies[]`): agregado de estoque `Supply`, rotas `/v1/supplies`. **Não** renomear para `Part` — o corpo deste plano às vezes escreve `Part`/`/v1/parts` por engano; leia como `Supply`/`/v1/supplies` (ver "Fonte da verdade").
- **DynamoDB single-table**: tabela `auto-repair-shop-execution-{env}`, chaves `pk`/`sk` (S), GSI `gsi1` (`gsi1pk`/`gsi1sk`, projection ALL). Nome lido de env `DYNAMODB_TABLE_NAME` (SSM `/auto-repair-shop/{env}/execution/dynamodb/table-name`).
- **SNS**: tópico do produtor via env `SNS_TOPIC_ARN` (SSM `/auto-repair-shop/{env}/sns/execution-events-topic-arn`). Todo publish leva message attribute `eventType` (String, **camelCase** — ex. `PartsReserved`) + `traceparent`. **Não** replicar o `event_type` snake_case do monólito.
- **SQS**: fila consumidora via env `SQS_QUEUE_URL` (SSM `/auto-repair-shop/{env}/sqs/execution-saga-queue-url`). Body = envelope JSON cru (raw delivery). Despacho por `envelope.eventType`; evento sem handler é ignorado (marcado processado).
- **Envelope** (body de todo evento): `{ eventId, eventType, eventVersion, occurredAt, payload }`. `eventVersion` começa em 1. `orderId` é a chave da saga em todo evento; `reservationId` nasce no `PartsReserved`.
- **Idempotência**: dedup por `(eventId, consumerId)` na tabela (item `PROC#...`), conditional put. Além disso, toda mutação de saga usa escrita condicional (ver Task 11) — o serviço é seguro sob entrega duplicada e **múltiplas réplicas**, por isso **não** usamos ShedLock (o monólito usava; aqui não há JDBC).
- **AWS creds**: em prod/hml, cadeia default do SDK (node role LabRole, ADR-002 — **não criar IAM role/policy**). Em testes, credenciais estáticas do LocalStack via `aws.endpoint`/`aws.accessKeyId`/`aws.secretAccessKey` no config.
- **Deploy**: containerPort 8080, Service **NodePort 30082**, imagem `ghcr.io/ivanzao/auto-repair-shop-execution`, namespace `auto-repair-shop-{env}`. **Sem** Secrets Manager/ExternalSecret (execution só lê SSM). Listener NLB 8082 e rotas do gateway já provisionados pela infra (Plano 1).
- **Qualidade**: JaCoCo agregado ≥ 80% com gate; SonarCloud no `pr-check`; branch `main` protegida (PR + checks). Sem BDD (mora no order).
- **TTL de reserva**: default 7 dias, config `reservation.ttl.days` (env `RESERVATION_TTL_DAYS`).

## Estrutura de arquivos (mapa)

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew*, Dockerfile, .gitignore, .dockerignore
domain/   → modelos, use cases, portas (repositories), eventos de saga, envelope
api/      → Ktor: PartRoutes, ExecutionRoutes, config (auth/errors/serialization/observability)
storage/  → DynamoDB: DynamoDb provider, key helpers, *DynamoRepository, ProcessedEventRepository, OutboxRepository
worker/   → SnsPublisher, SqsConsumerWorker, envelope (de)serialização, OutboxRelayTask, ReservationExpiredTask, ScheduledTaskRunner
main/     → Main.kt (Koin wiring), KtorHttpServer, application.yaml
metric/   → MicrometerMetricsPort (clone)
infra/k8s/→ base + overlays hml/prod
.github/workflows/ → pr-check.yaml, build-and-deploy.yaml
```

Cada tipo de item DynamoDB e sua modelagem de chave:

| Item | pk / sk | gsi1pk / gsi1sk (esparso) | Uso |
|---|---|---|---|
| Part | `PART#{id}` / `PART#{id}` | `PART` / `{name}` | estoque; listagem `/v1/parts` |
| Execution | `ORDER#{orderId}` / `ORDER#{orderId}` | `EXEC#{status}` / `{occurredAt}` | agregado da OS: quote gordo + status; fila do mecânico |
| Reservation | `RES#{reservationId}` / `RES#{reservationId}` | `RES#ACTIVE` / `{expiresAt}` | linhas reservadas; job de expiração |
| Outbox | `OUTBOX#{eventId}` / `OUTBOX#{eventId}` | `OUTBOX#PENDING` / `{occurredAt}` | relay → SNS |
| ProcessedEvent | `PROC#{eventId}` / `CONS#{consumerId}` | — | dedup |

---

## Task 1: Bootstrap do repositório Gradle multi-módulo

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `gradlew`, `gradlew.bat`, `gradle/wrapper/*`, `.gitignore`, `.dockerignore`, `Dockerfile`
- Create: `domain/build.gradle.kts`, `api/build.gradle.kts`, `storage/build.gradle.kts`, `worker/build.gradle.kts`, `main/build.gradle.kts`, `metric/build.gradle.kts`
- Create: `domain/src/main/kotlin/br/com/soat/.gitkeep` (e demais módulos)

**Interfaces:**
- Produces: projeto Gradle que compila (`./gradlew build`) com 6 módulos vazios.

- [ ] **Step 1: Inicializar repo e copiar o wrapper do monólito**

```bash
cd /home/ivanzao/dev/repository/auto-repair-shop-execution
git init
cp /home/ivanzao/dev/repository/auto-repair-shop/gradlew /home/ivanzao/dev/repository/auto-repair-shop/gradlew.bat .
cp -r /home/ivanzao/dev/repository/auto-repair-shop/gradle/wrapper gradle/
cp /home/ivanzao/dev/repository/auto-repair-shop/gradle.properties .
cp /home/ivanzao/dev/repository/auto-repair-shop/.gitignore /home/ivanzao/dev/repository/auto-repair-shop/.dockerignore .
```

- [ ] **Step 2: `settings.gradle.kts`** (mesmos módulos do monólito)

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "auto-repair-shop-execution"

include("main")
include("domain")
include("api")
include("storage")
include("worker")
include("metric")
```

- [ ] **Step 3: `gradle/libs.versions.toml`** — copiar o do monólito **e adicionar a lib do DynamoDB**:

```bash
cp /home/ivanzao/dev/repository/auto-repair-shop/gradle/libs.versions.toml gradle/libs.versions.toml
```

Depois, na seção `# AWS SDK Kotlin`, adicionar (o `sqs`/`sns` já existem):

```toml
aws-sdk-kotlin-dynamodb = { module = "aws.sdk.kotlin:dynamodb", version.ref = "aws-sdk-kotlin" }
```

- [ ] **Step 4: `build.gradle.kts` (root)** — copiar o do monólito trocando o `projectKey`/`projectName` do Sonar:

```bash
cp /home/ivanzao/dev/repository/auto-repair-shop/build.gradle.kts build.gradle.kts
```

No bloco `sonar { properties { ... } }`, trocar:

```kotlin
        property("sonar.projectKey", "auto-repair-shop-execution")
        property("sonar.projectName", "Auto Repair Shop Execution")
```

- [ ] **Step 5: build files dos módulos**

`domain/build.gradle.kts` — copiar de `../auto-repair-shop/domain/build.gradle.kts` sem alterações.

`metric/build.gradle.kts` — copiar de `../auto-repair-shop/metric/build.gradle.kts`.

`api/build.gradle.kts` — copiar de `../auto-repair-shop/api/build.gradle.kts`.

`storage/build.gradle.kts` — **substituir** o conteúdo (troca Exposed/Postgres por DynamoDB):

```kotlin
dependencies {
    implementation(project(":domain"))

    implementation(libs.aws.sdk.kotlin.dynamodb)

    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

`worker/build.gradle.kts` — **substituir** (adiciona sqs + dynamodb + jackson; remove shedlock):

```kotlin
plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    implementation(libs.aws.sdk.kotlin.sns)
    implementation(libs.aws.sdk.kotlin.sqs)
    implementation(libs.aws.sdk.kotlin.dynamodb)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.datatype.jsr310)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
}
```

`main/build.gradle.kts` — copiar de `../auto-repair-shop/main/build.gradle.kts` e **trocar** as dependências de teste de Postgres/Exposed pela stack DynamoDB. Substituir o bloco `dependencies { ... }` de teste por:

```kotlin
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.jackson.module.kotlin)
    testImplementation(libs.jackson.databind)
    testImplementation(libs.jackson.datatype.jsr310)
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.aws.sdk.kotlin.sqs)
    testImplementation(libs.aws.sdk.kotlin.sns)
    testImplementation(libs.aws.sdk.kotlin.dynamodb)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testRuntimeOnly(libs.junit.platform.launcher)
```

(remover `testcontainers.postgresql` e `exposed.core` das dependências de teste.)

- [ ] **Step 6: placeholders de source dir** — criar `src/main/kotlin/br/com/soat/.gitkeep` em cada módulo para o Gradle não reclamar.

```bash
for m in domain api storage worker main metric; do mkdir -p $m/src/main/kotlin/br/com/soat && touch $m/src/main/kotlin/br/com/soat/.gitkeep; done
mkdir -p main/src/main/resources
```

- [ ] **Step 7: `Dockerfile`** — copiar do monólito e trocar o nome do jar path (é o mesmo `:main:shadowJar`, então cópia direta serve):

```bash
cp /home/ivanzao/dev/repository/auto-repair-shop/Dockerfile Dockerfile
```

- [ ] **Step 8: Verificar build vazio**

Run: `./gradlew build --no-daemon`
Expected: `BUILD SUCCESSFUL` (módulos sem código compilam).

- [ ] **Step 9: Commit**

```bash
git add -A && git commit -m "chore: bootstrap gradle multi-module skeleton for execution service"
```

---

## Task 2: Config, Ktor server e health/metrics

**Files:**
- Create (clone do monólito, sem alteração de código, só o pacote se mantém `br.com.soat`):
  - `domain/src/main/kotlin/br/com/soat/config/Config.kt`
  - `main/src/main/kotlin/br/com/soat/config/ConfigFactory.kt`
  - `api/src/main/kotlin/br/com/soat/HttpServer.kt`, `api/src/main/kotlin/br/com/soat/KtorHttpServer.kt`
  - `api/src/main/kotlin/br/com/soat/config/{ErrorHandlingConfiguration,SerializationConfiguration,ObservabilityConfiguration,RoutingConfiguration,AuthenticationConfiguration}.kt`
  - `api/src/main/kotlin/br/com/soat/auth/{JwtBearerAuthenticationProvider,JwtClaims}.kt`
  - `api/src/main/kotlin/br/com/soat/shared/RouteExtensions.kt`, `api/src/main/kotlin/br/com/soat/shared/dto/{ErrorResponseDTO,PageResponseDTO,ValidationErrorDTO}.kt`
  - `domain/src/main/kotlin/br/com/soat/metric/MetricsPort.kt`, `metric/src/main/kotlin/br/com/soat/metric/MicrometerMetricsPort.kt`
  - `domain/src/main/kotlin/br/com/soat/shared/exception/ApplicationException.kt`, `domain/src/main/kotlin/br/com/soat/shared/model/Page.kt`
  - `main/src/main/kotlin/br/com/soat/config/*` (prometheusMeterRegistry helper — ver `../auto-repair-shop/api/src/main/kotlin/br/com/soat/config/ObservabilityConfiguration.kt`)
- Create: `main/src/main/kotlin/br/com/soat/Main.kt` (versão mínima), `main/src/main/resources/application.yaml`, `main/src/main/resources/logback.xml` (clone), `main/src/main/resources/application-test.yaml`

**Interfaces:**
- Consumes: `Config` do Task 1.
- Produces: `applicationModule` (Koin) parcial; app que sobe e responde `GET /health` 200 e `GET /metrics`. `KtorHttpServer(koin, port, wait)`.

- [ ] **Step 1: Clonar os arquivos de infraestrutura HTTP/config** listados em Files a partir dos caminhos equivalentes no monólito. Remover, no `RoutingConfiguration.kt` clonado, os registros de rotas de domínio (`customerRoutes`, `orderRoutes`, etc.) — deixar só `/health`, `/metrics` e Swagger. As rotas de `parts`/`executions` entram nas Tasks 4 e 13.

- [ ] **Step 2: `application.yaml`** (mínimo; sem Postgres):

```yaml
application:
  url: http://localhost:8080
  profile: dev

server:
  port: 8080

aws:
  region: us-east-1

dynamodb:
  table:
    name: auto-repair-shop-execution-dev

sns:
  topic:
    arn: "arn:aws:sns:us-east-1:000000000000:auto-repair-shop-execution-events"

sqs:
  queue:
    url: "http://localhost:4566/000000000000/auto-repair-shop-execution-saga"

reservation:
  ttl:
    days: 7
```

> Nota: `ConfigFactory` já sobrepõe qualquer chave por env var (ex. `DYNAMODB_TABLE_NAME`, `SNS_TOPIC_ARN`, `SQS_QUEUE_URL`, `RESERVATION_TTL_DAYS`). É assim que o deploy injeta os valores do SSM.

- [ ] **Step 3: `Main.kt` mínimo** (só config + server; workers entram nas Tasks 7–8):

```kotlin
package br.com.soat

import br.com.soat.config.Config
import br.com.soat.config.fromClasspath
import br.com.soat.config.prometheusMeterRegistry
import br.com.soat.metric.MetricsPort
import br.com.soat.metric.MicrometerMetricsPort
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.koin.core.context.startKoin
import org.koin.dsl.module
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("br.com.soat.MainKt")

fun main() {
    logger.info("Starting auto-repair-shop-execution application")
    val koinApplication = startKoin { modules(applicationModule) }
    val config = koinApplication.koin.get<Config>()
    KtorHttpServer(koin = koinApplication.koin, port = config.getInt("server.port"), wait = true).start()
}

val applicationModule = module {
    single<Config> { Config.fromClasspath("application.yaml") }
    single<ObjectMapper> { jacksonObjectMapper().registerModule(JavaTimeModule()) }
    single<PrometheusMeterRegistry> { prometheusMeterRegistry() }
    single<MeterRegistry> { get<PrometheusMeterRegistry>() }
    single<MetricsPort> { MicrometerMetricsPort(get<MeterRegistry>()) }
}
```

- [ ] **Step 4: `application-test.yaml`** — igual ao `application.yaml` mas `profile: test` (os valores AWS são sobrescritos em runtime pelo harness da Task 15).

- [ ] **Step 5: Rodar o app localmente e verificar health**

Run: `./gradlew :main:run &` (aguardar log "Responding at"); depois `curl -s -o /dev/null -w "%{http_code}" localhost:8080/health`
Expected: `200`. Encerrar o processo em seguida.

- [ ] **Step 6: Commit**

```bash
git add -A && git commit -m "feat: add config, ktor server and health/metrics endpoints"
```

---

## Task 3: Provider DynamoDB + helpers de chave e mapeamento

**Files:**
- Create: `storage/src/main/kotlin/br/com/soat/storage/DynamoDb.kt`
- Create: `storage/src/main/kotlin/br/com/soat/storage/Keys.kt`
- Create: `storage/src/main/kotlin/br/com/soat/storage/AttributeValues.kt`
- Test: `storage/src/test/kotlin/br/com/soat/storage/KeysTest.kt`

**Interfaces:**
- Produces:
  - `class DynamoDb(val client: aws.sdk.kotlin.services.dynamodb.DynamoDbClient, val tableName: String) : AutoCloseable` — factory `DynamoDb.create(config)`.
  - `object Keys` com `part(id): String = "PART#$id"`, `order(id)`, `reservation(id)`, `processed(eventId)`, `consumer(id)`, `outbox(eventId)`, e constantes `GSI = "gsi1"`, `PART_LIST = "PART"`, `OUTBOX_PENDING = "OUTBOX#PENDING"`, `RES_ACTIVE = "RES#ACTIVE"`, `execStatus(status) = "EXEC#$status"`.
  - helpers `s(String): AttributeValue`, `n(Number)`, `n(BigDecimal)`, `Map<String,AttributeValue>.str(key)`, `.int(key)`, `.decimal(key)`, `.longOrNull(key)`.

- [ ] **Step 1: Teste dos helpers de chave**

```kotlin
package br.com.soat.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class KeysTest {
    @Test
    fun `builds prefixed keys`() {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        assertEquals("PART#$id", Keys.part(id))
        assertEquals("ORDER#$id", Keys.order(id))
        assertEquals("RES#$id", Keys.reservation(id))
        assertEquals("EXEC#QUEUED", Keys.execStatus("QUEUED"))
    }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./gradlew :storage:test --tests '*KeysTest*'` → FAIL (Keys não existe).

- [ ] **Step 3: `Keys.kt`**

```kotlin
package br.com.soat.storage

import java.util.UUID

object Keys {
    const val GSI = "gsi1"
    const val PART_LIST = "PART"
    const val OUTBOX_PENDING = "OUTBOX#PENDING"
    const val RES_ACTIVE = "RES#ACTIVE"

    fun part(id: UUID) = "PART#$id"
    fun order(id: UUID) = "ORDER#$id"
    fun reservation(id: UUID) = "RES#$id"
    fun outbox(eventId: UUID) = "OUTBOX#$eventId"
    fun processed(eventId: UUID) = "PROC#$eventId"
    fun consumer(consumerId: String) = "CONS#$consumerId"
    fun execStatus(status: String) = "EXEC#$status"
}
```

- [ ] **Step 4: `AttributeValues.kt`** (açúcar sobre o SDK):

```kotlin
package br.com.soat.storage

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import java.math.BigDecimal

fun s(value: String): AttributeValue = AttributeValue.S(value)
fun n(value: Number): AttributeValue = AttributeValue.N(value.toString())
fun n(value: BigDecimal): AttributeValue = AttributeValue.N(value.toPlainString())

fun Map<String, AttributeValue>.str(key: String): String =
    (this[key] as? AttributeValue.S)?.value ?: error("Missing string attribute '$key'")

fun Map<String, AttributeValue>.strOrNull(key: String): String? =
    (this[key] as? AttributeValue.S)?.value

fun Map<String, AttributeValue>.int(key: String): Int =
    (this[key] as? AttributeValue.N)?.value?.toInt() ?: error("Missing number attribute '$key'")

fun Map<String, AttributeValue>.decimal(key: String): BigDecimal =
    (this[key] as? AttributeValue.N)?.value?.let(::BigDecimal) ?: error("Missing number attribute '$key'")
```

- [ ] **Step 5: `DynamoDb.kt`** (provider; espelha o padrão do `SnsClient` do monólito para credenciais/endpoint):

```kotlin
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
```

- [ ] **Step 6: Rodar teste e passar** — `./gradlew :storage:test --tests '*KeysTest*'` → PASS.

- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat: add dynamodb provider and single-table key helpers"`

---

## Task 4: Domínio Part + repositório DynamoDB + REST /v1/parts

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/part/model/Part.kt`, `.../part/model/PartRequirement.kt`, `.../part/model/request/CreatePartRequest.kt`
- Create: `domain/src/main/kotlin/br/com/soat/part/repository/PartRepository.kt`
- Create: `domain/src/main/kotlin/br/com/soat/part/PartUseCase.kt`, `.../part/exception/PartExceptions.kt`
- Create: `storage/src/main/kotlin/br/com/soat/part/PartDynamoRepository.kt`
- Create: `api/src/main/kotlin/br/com/soat/part/PartRoutes.kt`, `.../part/dto/{CreatePartRequestDTO,PartResponseDTO}.kt`
- Test: `domain/src/test/kotlin/br/com/soat/part/PartUseCaseTest.kt`, `storage/src/test/kotlin/br/com/soat/part/PartDynamoRepositoryTest.kt` (integração, LocalStack)

**Interfaces:**
- Produces:
  - `data class Part(id: UUID = randomUUID(), createdAt, modifiedAt, version: Int = 0, name: String, description: String?, quantityInStock: Int, price: BigDecimal)`
  - `data class PartRequirement(partId: UUID, quantity: Int)`
  - `interface PartRepository { findById(id): Part?; findAll(): List<Part>; findAllByIds(ids): List<Part>; create(part): Part; update(part): Part; delete(id) }`
  - `class PartUseCase(repo)` com `findById/findAll/create/update/delete`.
  - `PartExceptions`: `PartNotFoundException(id)`, `InsufficientStockException(partId)`.

- [ ] **Step 1: Modelos + porta + exceptions** — clonar de `../auto-repair-shop/domain/src/main/kotlin/br/com/soat/supply/` renomeando `Supply`→`Part`, `supplyId`→`partId`, `SupplyRequirement`→`PartRequirement`. `Part` = cópia de `Supply.kt`. `PartRepository` = cópia de `SupplyRepository.kt` (mesma assinatura). `PartExceptions` idem.

- [ ] **Step 2: Teste do use case (unit, MockK)** — clonar a lógica de `SupplyUseCase` (create/update/delete/findById). Exemplo:

```kotlin
package br.com.soat.part

import br.com.soat.part.exception.PartNotFoundException
import br.com.soat.part.model.request.CreatePartRequest
import br.com.soat.part.repository.PartRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class PartUseCaseTest {
    private val repo = mockk<PartRepository>(relaxed = true)
    private val useCase = PartUseCase(repo)

    @Test
    fun `findById throws when not found`() {
        val id = UUID.randomUUID()
        every { repo.findById(id) } returns null
        assertThrows(PartNotFoundException::class.java) { useCase.findById(id) }
    }
}
```

- [ ] **Step 3: `PartUseCase`** — clonar `SupplyUseCase.kt` renomeando.

- [ ] **Step 4: `PartDynamoRepository`** (implementação single-table):

```kotlin
package br.com.soat.part

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.DeleteItemRequest
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import br.com.soat.part.model.Part
import br.com.soat.part.repository.PartRepository
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.decimal
import br.com.soat.storage.int
import br.com.soat.storage.n
import br.com.soat.storage.s
import br.com.soat.storage.str
import br.com.soat.storage.strOrNull
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking

class PartDynamoRepository(private val db: DynamoDb) : PartRepository {

    private fun Part.toItem(): Map<String, AttributeValue> = buildMap {
        put("pk", s(Keys.part(id)))
        put("sk", s(Keys.part(id)))
        put("gsi1pk", s(Keys.PART_LIST))
        put("gsi1sk", s(name))
        put("type", s("PART"))
        put("id", s(id.toString()))
        put("name", s(name))
        description?.let { put("description", s(it)) }
        put("quantityInStock", n(quantityInStock))
        put("price", n(price))
        put("version", n(version))
        put("createdAt", s(createdAt.toString()))
        put("modifiedAt", s(modifiedAt.toString()))
    }

    private fun Map<String, AttributeValue>.toPart() = Part(
        id = UUID.fromString(str("id")),
        createdAt = LocalDateTime.parse(str("createdAt")),
        modifiedAt = LocalDateTime.parse(str("modifiedAt")),
        version = int("version"),
        name = str("name"),
        description = strOrNull("description"),
        quantityInStock = int("quantityInStock"),
        price = decimal("price"),
    )

    override fun findById(id: UUID): Part? = runBlocking {
        db.client.getItem(GetItemRequest {
            tableName = db.tableName
            key = mapOf("pk" to s(Keys.part(id)), "sk" to s(Keys.part(id)))
        }).item?.toPart()
    }

    override fun findAll(): List<Part> = runBlocking {
        db.client.query(QueryRequest {
            tableName = db.tableName
            indexName = Keys.GSI
            keyConditionExpression = "gsi1pk = :pk"
            expressionAttributeValues = mapOf(":pk" to s(Keys.PART_LIST))
        }).items.orEmpty().map { it.toPart() }
    }

    override fun findAllByIds(ids: List<UUID>): List<Part> = ids.mapNotNull { findById(it) }

    override fun create(part: Part): Part = runBlocking {
        db.client.putItem(PutItemRequest {
            tableName = db.tableName
            item = part.toItem()
            conditionExpression = "attribute_not_exists(pk)"
        })
        part
    }

    override fun update(part: Part): Part = runBlocking {
        val updated = part.copy(version = part.version + 1, modifiedAt = LocalDateTime.now())
        db.client.putItem(PutItemRequest {
            tableName = db.tableName
            item = updated.toItem()
            conditionExpression = "attribute_exists(pk)"
        })
        updated
    }

    override fun delete(id: UUID): Unit = runBlocking {
        db.client.deleteItem(DeleteItemRequest {
            tableName = db.tableName
            key = mapOf("pk" to s(Keys.part(id)), "sk" to s(Keys.part(id)))
        })
        Unit
    }
}
```

- [ ] **Step 5: Teste de integração do repositório** (LocalStack DynamoDB). Criar um `DynamoTestSupport` reutilizável (fixture que sobe `LocalStackContainer(DYNAMODB)`, cria a tabela com pk/sk + GSI gsi1, e devolve `DynamoDb`). Teste:

```kotlin
// PartDynamoRepositoryTest: cria Part, findById devolve igual; findAll lista; update incrementa version; delete remove.
```

Usar o helper de criação de tabela (colocar em `storage/src/test/kotlin/br/com/soat/storage/DynamoTestSupport.kt`):

```kotlin
package br.com.soat.storage

import aws.sdk.kotlin.services.dynamodb.model.*
import kotlinx.coroutines.runBlocking

fun DynamoDb.createExecutionTable() = runBlocking {
    client.createTable(CreateTableRequest {
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
        globalSecondaryIndexes = listOf(GlobalSecondaryIndex {
            indexName = "gsi1"
            keySchema = listOf(
                KeySchemaElement { attributeName = "gsi1pk"; keyType = KeyType.Hash },
                KeySchemaElement { attributeName = "gsi1sk"; keyType = KeyType.Range },
            )
            projection = Projection { projectionType = ProjectionType.All }
        })
    })
}
```

- [ ] **Step 6: `PartRoutes` + DTOs** — clonar de `../auto-repair-shop/api/src/main/kotlin/br/com/soat/supply/` renomeando para `part`/`/v1/parts`. Endpoints: `GET /v1/parts`, `GET /v1/parts/{id}`, `POST /v1/parts`, `PUT /v1/parts/{id}`, `DELETE /v1/parts/{id}`. Registrar em `RoutingConfiguration`.

- [ ] **Step 7: Rodar testes** — `./gradlew :domain:test :storage:integrationTest --tests '*Part*'` → PASS.

- [ ] **Step 8: Commit** — `git add -A && git commit -m "feat: add part domain, dynamodb repository and /v1/parts routes"`

---

## Task 5: Idempotência — ProcessedEventRepository

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/event/ProcessedEventRepository.kt` (porta)
- Create: `storage/src/main/kotlin/br/com/soat/event/ProcessedEventDynamoRepository.kt`
- Test: `storage/src/test/kotlin/br/com/soat/event/ProcessedEventDynamoRepositoryTest.kt`

**Interfaces:**
- Produces: `interface ProcessedEventRepository { fun markProcessed(eventId: UUID, consumerId: String): Boolean; fun isProcessed(eventId: UUID, consumerId: String): Boolean }`. `markProcessed` retorna `true` se **gravou agora** (primeira vez), `false` se já existia (conditional put falhou) — é o guard de dedup.

- [ ] **Step 1: Teste** — grava `(evt, "H")` → `markProcessed` retorna true; segunda chamada retorna false; `isProcessed` true.

- [ ] **Step 2: Porta** — `ProcessedEventRepository.kt` como acima.

- [ ] **Step 3: Implementação**

```kotlin
package br.com.soat.event

import aws.sdk.kotlin.services.dynamodb.model.ConditionalCheckFailedException
import aws.sdk.kotlin.services.dynamodb.model.GetItemRequest
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.s
import java.time.LocalDateTime
import java.util.UUID
import kotlinx.coroutines.runBlocking

class ProcessedEventDynamoRepository(private val db: DynamoDb) : ProcessedEventRepository {

    override fun markProcessed(eventId: UUID, consumerId: String): Boolean = runBlocking {
        try {
            db.client.putItem(PutItemRequest {
                tableName = db.tableName
                item = mapOf(
                    "pk" to s(Keys.processed(eventId)),
                    "sk" to s(Keys.consumer(consumerId)),
                    "processedAt" to s(LocalDateTime.now().toString()),
                )
                conditionExpression = "attribute_not_exists(pk)"
            })
            true
        } catch (_: ConditionalCheckFailedException) {
            false
        }
    }

    override fun isProcessed(eventId: UUID, consumerId: String): Boolean = runBlocking {
        db.client.getItem(GetItemRequest {
            tableName = db.tableName
            key = mapOf("pk" to s(Keys.processed(eventId)), "sk" to s(Keys.consumer(consumerId)))
        }).item != null
    }
}
```

- [ ] **Step 4: Rodar teste** → PASS. **Step 5: Commit** — `git add -A && git commit -m "feat: add dynamodb processed-events idempotency store"`

---

## Task 6: Envelope de evento + OutboxRepository

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/event/model/EventEnvelope.kt`
- Create: `domain/src/main/kotlin/br/com/soat/event/model/SagaEventType.kt`
- Create: `domain/src/main/kotlin/br/com/soat/event/OutboxRepository.kt` (porta)
- Create: `storage/src/main/kotlin/br/com/soat/event/OutboxDynamoRepository.kt`
- Test: `storage/src/test/kotlin/br/com/soat/event/OutboxDynamoRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `data class EventEnvelope(eventId: UUID = randomUUID(), eventType: String, eventVersion: Int = 1, occurredAt: String = Instant.now().toString(), payload: JsonNode)`.
  - `object SagaEventType` com constantes String: `ORDER_CREATED="OrderCreated"`, `PARTS_RESERVED="PartsReserved"`, `PARTS_UNAVAILABLE="PartsUnavailable"`, `PAYMENT_CONFIRMED="PaymentConfirmed"`, `EXECUTION_STARTED="ExecutionStarted"`, `DIAGNOSE_FINISHED="DiagnoseFinished"`, `EXECUTION_FINISHED="ExecutionFinished"`, `EXECUTION_FAILED="ExecutionFailed"`, `QUOTE_REJECTED="QuoteRejected"`, `PAYMENT_FAILED="PaymentFailed"`, `RESERVATION_EXPIRED="ReservationExpired"`.
  - `interface OutboxRepository { fun putItem(env: EventEnvelope): Map<String,AttributeValue>; fun pending(limit: Int): List<EventEnvelope>; fun markPublished(eventId: UUID) }`. `putItem` **devolve o item** (não grava) para poder entrar num `TransactWriteItems` junto com a mutação de negócio (Task 11). Há também `fun save(env)` para o caso de escrita isolada.

- [ ] **Step 1: `EventEnvelope` e `SagaEventType`** — records simples (payload como `com.fasterxml.jackson.databind.JsonNode`).

- [ ] **Step 2: Teste do outbox** — `save(env)` grava com `gsi1pk=OUTBOX#PENDING`; `pending(10)` devolve; `markPublished` remove do GSI (o `pending` seguinte volta vazio).

- [ ] **Step 3: Implementação**

```kotlin
package br.com.soat.event

import aws.sdk.kotlin.services.dynamodb.model.AttributeValue
import aws.sdk.kotlin.services.dynamodb.model.PutItemRequest
import aws.sdk.kotlin.services.dynamodb.model.QueryRequest
import aws.sdk.kotlin.services.dynamodb.model.UpdateItemRequest
import br.com.soat.event.model.EventEnvelope
import br.com.soat.storage.DynamoDb
import br.com.soat.storage.Keys
import br.com.soat.storage.n
import br.com.soat.storage.s
import br.com.soat.storage.str
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import kotlinx.coroutines.runBlocking

class OutboxDynamoRepository(
    private val db: DynamoDb,
    private val mapper: ObjectMapper,
) : OutboxRepository {

    override fun putItem(env: EventEnvelope): Map<String, AttributeValue> = mapOf(
        "pk" to s(Keys.outbox(env.eventId)),
        "sk" to s(Keys.outbox(env.eventId)),
        "gsi1pk" to s(Keys.OUTBOX_PENDING),
        "gsi1sk" to s(env.occurredAt),
        "type" to s("OUTBOX"),
        "eventId" to s(env.eventId.toString()),
        "eventType" to s(env.eventType),
        "eventVersion" to n(env.eventVersion),
        "occurredAt" to s(env.occurredAt),
        "payload" to s(mapper.writeValueAsString(env.payload)),
    )

    override fun save(env: EventEnvelope): Unit = runBlocking {
        db.client.putItem(PutItemRequest { tableName = db.tableName; item = putItem(env) })
        Unit
    }

    override fun pending(limit: Int): List<EventEnvelope> = runBlocking {
        db.client.query(QueryRequest {
            tableName = db.tableName
            indexName = Keys.GSI
            keyConditionExpression = "gsi1pk = :pk"
            expressionAttributeValues = mapOf(":pk" to s(Keys.OUTBOX_PENDING))
            this.limit = limit
        }).items.orEmpty().map {
            EventEnvelope(
                eventId = UUID.fromString(it.str("eventId")),
                eventType = it.str("eventType"),
                eventVersion = (it["eventVersion"] as AttributeValue.N).value.toInt(),
                occurredAt = it.str("occurredAt"),
                payload = mapper.readTree(it.str("payload")),
            )
        }
    }

    override fun markPublished(eventId: UUID): Unit = runBlocking {
        db.client.updateItem(UpdateItemRequest {
            tableName = db.tableName
            key = mapOf("pk" to s(Keys.outbox(eventId)), "sk" to s(Keys.outbox(eventId)))
            updateExpression = "REMOVE gsi1pk, gsi1sk"
        })
        Unit
    }
}
```

(Adicionar `fun save(env: EventEnvelope)` à porta `OutboxRepository`.)

- [ ] **Step 4: Rodar teste** → PASS. **Step 5: Commit** — `git add -A && git commit -m "feat: add event envelope and dynamodb outbox repository"`

---

## Task 7: Publisher SNS + OutboxRelayTask + ScheduledTaskRunner

**Files:**
- Create: `worker/src/main/kotlin/br/com/soat/messaging/SnsPublisher.kt`
- Create: `worker/src/main/kotlin/br/com/soat/scheduler/ScheduledTask.kt` (clone), `.../scheduler/ScheduledTaskRunner.kt` (versão sem ShedLock)
- Create: `worker/src/main/kotlin/br/com/soat/scheduler/task/OutboxRelayTask.kt`
- Test: `worker/src/test/kotlin/br/com/soat/scheduler/task/OutboxRelayTaskTest.kt`

**Interfaces:**
- Consumes: `OutboxRepository` (Task 6).
- Produces:
  - `class SnsPublisher(topicArn, region, endpointOverride?, accessKeyId?, secretAccessKey?)` com `fun publish(body: String, eventType: String, traceparent: String? = null)` — attribute `eventType` (camelCase) + `traceparent` quando presente. Adaptado do `SnsClient` do monólito.
  - `interface ScheduledTask { execute(); getName(): String; getIntervalInSeconds(): Long }`.
  - `class ScheduledTaskRunner(tasks)` com `start()` / `stop()` — **sem** ShedLock, `ScheduledExecutorService`.
  - `class OutboxRelayTask(outbox, snsPublisher, mapper)`.

- [ ] **Step 1: `SnsPublisher`** — copiar `../auto-repair-shop/worker/.../messaging/SnsClient.kt`, renomear classe para `SnsPublisher`, trocar o mapa de attributes para:

```kotlin
messageAttributes = buildMap {
    put("eventType", MessageAttributeValue { dataType = "String"; stringValue = eventType })
    if (traceparent != null) put("traceparent", MessageAttributeValue { dataType = "String"; stringValue = traceparent })
}
```

e a assinatura `fun publish(body: String, eventType: String, traceparent: String? = null)`.

- [ ] **Step 2: `ScheduledTask`** — copiar interface do monólito **mas** renomear `getLockName()` → `getName()` (não há mais lock).

- [ ] **Step 3: `ScheduledTaskRunner` sem ShedLock**

```kotlin
package br.com.soat.scheduler

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ScheduledTaskRunner(private val tasks: List<ScheduledTask>) {
    private val logger = LoggerFactory.getLogger(ScheduledTaskRunner::class.java)
    private lateinit var scheduler: ScheduledExecutorService

    fun start() {
        scheduler = Executors.newScheduledThreadPool(tasks.size.coerceAtLeast(1))
        tasks.forEach { task ->
            scheduler.scheduleAtFixedRate({
                try { task.execute() } catch (e: Exception) { logger.error("Task ${task.getName()} failed", e) }
            }, 0, task.getIntervalInSeconds(), TimeUnit.SECONDS)
            logger.info("Scheduled ${task.getName()} every ${task.getIntervalInSeconds()}s")
        }
    }

    fun stop() {
        if (::scheduler.isInitialized) {
            scheduler.shutdown()
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) scheduler.shutdownNow()
        }
    }
}
```

> Segurança multi-réplica: se duas réplicas relayarem o mesmo item, ambas publicam mas só uma faz `markPublished` — o downstream deduplica por `eventId`. Aceitável e documentado no README.

- [ ] **Step 4: Teste do relay (unit, MockK)** — `outbox.pending(N)` devolve 1 envelope; `execute()` chama `snsPublisher.publish(body, "PartsReserved", ...)` e `outbox.markPublished(id)`.

- [ ] **Step 5: `OutboxRelayTask`**

```kotlin
package br.com.soat.scheduler.task

import br.com.soat.event.OutboxRepository
import br.com.soat.messaging.SnsPublisher
import br.com.soat.scheduler.ScheduledTask
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

class OutboxRelayTask(
    private val outbox: OutboxRepository,
    private val sns: SnsPublisher,
    private val mapper: ObjectMapper,
) : ScheduledTask {
    private val logger = LoggerFactory.getLogger(OutboxRelayTask::class.java)

    override fun execute() {
        outbox.pending(10).forEach { env ->
            try {
                val body = mapper.writeValueAsString(env)
                sns.publish(body = body, eventType = env.eventType)
                outbox.markPublished(env.eventId)
            } catch (e: Exception) {
                logger.error("Failed to relay outbox event ${env.eventId}", e)
            }
        }
    }

    override fun getName() = "outbox-relay"
    override fun getIntervalInSeconds() = 5L
}
```

- [ ] **Step 6: Rodar teste** → PASS. **Step 7: Commit** — `git add -A && git commit -m "feat: add sns publisher, scheduler and outbox relay task"`

---

## Task 8: Consumidor SQS + despacho por eventType

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/event/SagaEventHandler.kt` (porta)
- Create: `worker/src/main/kotlin/br/com/soat/consumer/SqsConsumerWorker.kt`
- Create: `worker/src/main/kotlin/br/com/soat/consumer/SagaDispatcher.kt`
- Test: `worker/src/test/kotlin/br/com/soat/consumer/SagaDispatcherTest.kt`

**Interfaces:**
- Produces:
  - `interface SagaEventHandler { val eventType: String; fun handle(env: EventEnvelope) }`.
  - `class SagaDispatcher(handlers: List<SagaEventHandler>, processed: ProcessedEventRepository)` com `fun dispatch(env: EventEnvelope)` — para cada handler cujo `eventType` casa: se `processed.markProcessed(env.eventId, handlerName)` for true, roda `handle`; senão pula (dedup). Evento sem handler = no-op (log).
  - `class SqsConsumerWorker(queueUrl, region, endpoint?, creds?, dispatcher, mapper, dispatcherCoroutine)` com `start()` — long-poll, parse do envelope, `dispatch`, `deleteMessage` só em sucesso.

- [ ] **Step 1: `SagaEventHandler`** (porta no domain).

- [ ] **Step 2: Teste do dispatcher (MockK)**:
  - envelope `OrderCreated` com 1 handler casando → `handle` chamado 1x, `markProcessed` chamado.
  - `markProcessed` retorna false (duplicado) → `handle` **não** chamado.
  - envelope de tipo sem handler → nenhum `handle`, sem exceção.

- [ ] **Step 3: `SagaDispatcher`**

```kotlin
package br.com.soat.consumer

import br.com.soat.event.ProcessedEventRepository
import br.com.soat.event.SagaEventHandler
import br.com.soat.event.model.EventEnvelope
import org.slf4j.LoggerFactory

class SagaDispatcher(
    handlers: List<SagaEventHandler>,
    private val processed: ProcessedEventRepository,
) {
    private val logger = LoggerFactory.getLogger(SagaDispatcher::class.java)
    private val byType = handlers.groupBy { it.eventType }

    fun dispatch(env: EventEnvelope) {
        val matched = byType[env.eventType]
        if (matched.isNullOrEmpty()) {
            logger.debug("No handler for eventType=${env.eventType}, ignoring")
            return
        }
        matched.forEach { handler ->
            val consumerId = handler::class.simpleName!!
            if (processed.markProcessed(env.eventId, consumerId)) {
                handler.handle(env)
            } else {
                logger.info("Event ${env.eventId} already processed by $consumerId, skipping")
            }
        }
    }
}
```

> Nota: se `handle` lançar, a exceção sobe até o `SqsConsumerWorker`, que **não** deleta a mensagem → redelivery. O `markProcessed` já gravou, então o redelivery seria pulado — por isso o handler de reserva é idempotente por construção (Task 11, condição `attribute_not_exists` no ORDER#). Para operações não-idempotentes, o handler deve ser desenhado para tolerar reprocesso; documentar.

- [ ] **Step 4: `SqsConsumerWorker`** (long-poll; espelha o padrão coroutine do monólito):

```kotlin
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
                val messages = client.receiveMessage(ReceiveMessageRequest {
                    this.queueUrl = this@SqsConsumerWorker.queueUrl
                    maxNumberOfMessages = 10
                    waitTimeSeconds = 10
                }).messages.orEmpty()
                for (msg in messages) {
                    try {
                        val env = mapper.readValue(msg.body!!, EventEnvelope::class.java)
                        dispatcher.dispatch(env)
                        client.deleteMessage(DeleteMessageRequest {
                            this.queueUrl = this@SqsConsumerWorker.queueUrl
                            receiptHandle = msg.receiptHandle
                        })
                    } catch (e: Exception) {
                        logger.error("Failed to process SQS message ${msg.messageId}; leaving for redelivery", e)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 5: Rodar teste** → PASS. **Step 6: Commit** — `git add -A && git commit -m "feat: add sqs consumer worker and saga dispatcher"`

---

## Task 9: Agregado Execution + repositório + máquina de estados

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/execution/model/Execution.kt`, `.../execution/model/ExecutionStatus.kt`
- Create: `domain/src/main/kotlin/br/com/soat/execution/repository/ExecutionRepository.kt`
- Create: `domain/src/main/kotlin/br/com/soat/execution/exception/ExecutionExceptions.kt`
- Create: `storage/src/main/kotlin/br/com/soat/execution/ExecutionDynamoRepository.kt`
- Test: `domain/src/test/kotlin/br/com/soat/execution/ExecutionTest.kt`, `storage/src/test/kotlin/br/com/soat/execution/ExecutionDynamoRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `enum class ExecutionStatus { RESERVED, QUEUED, IN_PROGRESS, DIAGNOSED, COMPLETED, FAILED, CANCELED }`.
  - `data class Execution(orderId: UUID, status: ExecutionStatus, reservationId: UUID?, orderSnapshot: JsonNode, paymentId: String?, createdAt, modifiedAt)` — `orderSnapshot` é o payload cru do `OrderCreated` (guardado para repassar no `PartsReserved`). Métodos de transição que **validam** e devolvem cópia: `queue()`, `start()`, `finishDiagnosis()`, `finish()`, `fail()`, `cancel()`; cada um lança `InvalidExecutionTransitionException` se o estado atual não permite.
  - `interface ExecutionRepository { fun findByOrderId(id): Execution?; fun findByStatus(status): List<Execution>; fun putItem(e): Map<String,AttributeValue>; fun save(e) }`.

- [ ] **Step 1: Teste da máquina de estados** — `RESERVED.queue()` → QUEUED ok; `QUEUED.start()` → IN_PROGRESS; `RESERVED.finish()` lança `InvalidExecutionTransitionException`; `IN_PROGRESS.finishDiagnosis()` → DIAGNOSED; `DIAGNOSED.finish()` → COMPLETED; `IN_PROGRESS.fail()` → FAILED.

Transições válidas (documentar no `Execution`):

```
RESERVED --queue()--> QUEUED --start()--> IN_PROGRESS --finishDiagnosis()--> DIAGNOSED --finish()--> COMPLETED
IN_PROGRESS/DIAGNOSED --fail()--> FAILED
RESERVED/QUEUED --cancel()--> CANCELED   (compensações QuoteRejected/PaymentFailed/ReservationExpired)
```

- [ ] **Step 2: `ExecutionStatus`, `Execution` (com transições), exceptions.**

- [ ] **Step 3: `ExecutionDynamoRepository`** — `putItem`/`save` gravam pk/sk=`ORDER#{orderId}`, `gsi1pk=EXEC#{status}`, `gsi1sk=createdAt`, `orderSnapshot` como string JSON, `reservationId`/`paymentId` opcionais. `findByOrderId` = GetItem. `findByStatus` = Query no gsi1 por `EXEC#{status}`. Seguir o padrão do `PartDynamoRepository` (Task 4).

- [ ] **Step 4: Teste de integração do repositório** (LocalStack) — save + findByOrderId + findByStatus.

- [ ] **Step 5: Rodar testes** → PASS. **Step 6: Commit** — `git add -A && git commit -m "feat: add execution aggregate, state machine and dynamodb repository"`

---

## Task 10: Agregado Reservation + repositório

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/reservation/model/Reservation.kt`, `.../reservation/model/ReservationStatus.kt`, `.../reservation/model/ReservationLine.kt`
- Create: `domain/src/main/kotlin/br/com/soat/reservation/repository/ReservationRepository.kt`
- Create: `storage/src/main/kotlin/br/com/soat/reservation/ReservationDynamoRepository.kt`
- Test: `storage/src/test/kotlin/br/com/soat/reservation/ReservationDynamoRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `enum class ReservationStatus { ACTIVE, RELEASED, EXPIRED }`.
  - `data class ReservationLine(partId: UUID, quantity: Int)`.
  - `data class Reservation(id: UUID = randomUUID(), orderId: UUID, status: ReservationStatus = ACTIVE, lines: List<ReservationLine>, expiresAt: Instant, createdAt: Instant = now())`.
  - `interface ReservationRepository { fun findById(id): Reservation?; fun findActiveExpiredBefore(now: Instant, limit: Int): List<Reservation>; fun putItem(r): Map<String,AttributeValue>; fun deactivateItemUpdate(id, status): <parts p/ TransactWriteItems> }`. `findActiveExpiredBefore` = Query no gsi1 `RES#ACTIVE` com `gsi1sk < now`.

- [ ] **Step 1: Modelos** (Reservation/ReservationLine/ReservationStatus).

- [ ] **Step 2: `ReservationDynamoRepository`** — item: pk/sk=`RES#{id}`, `gsi1pk=RES#ACTIVE`, `gsi1sk={expiresAt ISO}` (esparso: só ativa tem gsi1pk), `orderId`, `status`, `lines` como lista de mapas (`L`/`M`), `expiresAt`, `createdAt`. `findActiveExpiredBefore(now)` → Query gsi1 `RES#ACTIVE` + `KeyConditionExpression "gsi1pk = :pk AND gsi1sk < :now"`. `deactivate` remove `gsi1pk/gsi1sk` e seta `status`.

Serialização de `lines`:

```kotlin
"lines" to AttributeValue.L(reservation.lines.map { line ->
    AttributeValue.M(mapOf("partId" to s(line.partId.toString()), "quantity" to n(line.quantity)))
})
```

- [ ] **Step 3: Teste de integração** — save ACTIVE com expiresAt no passado → `findActiveExpiredBefore(now)` acha; após deactivate → não acha.

- [ ] **Step 4: Rodar** → PASS. **Step 5: Commit** — `git add -A && git commit -m "feat: add reservation aggregate and dynamodb repository"`

---

## Task 11: Reserva de peças — OrderCreated → PartsReserved / PartsUnavailable

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/execution/ReservePartsUseCase.kt`
- Create: `domain/src/main/kotlin/br/com/soat/execution/TransactionalWriter.kt` (porta) + `storage/src/main/kotlin/br/com/soat/storage/DynamoTransactionalWriter.kt`
- Create: `domain/src/main/kotlin/br/com/soat/execution/handler/OrderCreatedHandler.kt`
- Test: `domain/src/test/kotlin/br/com/soat/execution/ReservePartsUseCaseTest.kt`, `main`/`storage` integração (Task 15)

**Interfaces:**
- Consumes: `PartRepository`, `ExecutionRepository`, `ReservationRepository`, `OutboxRepository`, `TransactionalWriter`, `ObjectMapper`, `Config` (ttl).
- Produces:
  - `interface TransactionalWriter { fun writeAll(puts: List<Map<String,AttributeValue>>, decrements: List<PartDecrement>): TxResult }` onde `PartDecrement(partId, quantity)` vira um `Update` condicional; `TxResult = SUCCESS | STOCK_CONFLICT | DUPLICATE`. Implementação usa `TransactWriteItems`; mapeia `TransactionCanceledException` → STOCK_CONFLICT/DUPLICATE conforme `cancellationReasons`.
  - `class ReservePartsUseCase(...)` com `fun reserve(order: OrderCreatedPayload)`.
  - `class OrderCreatedHandler(reserveParts, mapper) : SagaEventHandler` com `eventType = SagaEventType.ORDER_CREATED`.

- [ ] **Step 1: Parsing do payload** — `data class OrderCreatedPayload(orderId, customer, services, parts, totalAmount)` + `PartLine(id, name, quantity, unitPrice)` (Jackson). Deserializar de `env.payload`.

- [ ] **Step 2: Teste do use case (MockK)** — três cenários:
  1. estoque suficiente → `writer.writeAll` chamado com puts (reservation+execution+outbox `PartsReserved`) e decrements; verifica que o outbox `PartsReserved` carrega `reservationId`, `customer`, `services`, `parts`, `totalAmount` (repassa o quote priced).
  2. estoque insuficiente no pre-read → grava outbox `PartsUnavailable` com `missingParts` (requested/available); **não** chama decrement/reservation.
  3. `writer` devolve STOCK_CONFLICT (corrida) → grava outbox `PartsUnavailable`.

```kotlin
// exemplo do cenário 1 (esqueleto)
@Test
fun `reserves parts and emits PartsReserved carrying priced quote`() {
    every { partRepository.findAllByIds(any()) } returns listOf(Part(id = partId, name="Filtro", description=null, quantityInStock=5, price="30.00".toBigDecimal()))
    every { writer.writeAll(any(), any()) } returns TxResult.SUCCESS
    useCase.reserve(payload) // payload pede quantity=2 do partId
    verify { writer.writeAll(match { puts -> puts.any { it["eventType"]?.let { /* PartsReserved */ true } == true } }, match { it.single().quantity == 2 }) }
}
```

- [ ] **Step 3: `ReservePartsUseCase`** — lógica:

```kotlin
fun reserve(order: OrderCreatedPayload) {
    val orderId = order.orderId
    val required = order.parts.map { PartRequirement(it.id, it.quantity) }
    val parts = partRepository.findAllByIds(required.map { it.partId }).associateBy { it.id }

    // pre-read: monta faltas p/ PartsUnavailable
    val missing = required.mapNotNull { req ->
        val available = parts[req.partId]?.quantityInStock ?: 0
        if (available < req.quantity) MissingPart(req.partId, parts[req.partId]?.name ?: "?", req.quantity, available) else null
    }
    if (missing.isNotEmpty()) { emitPartsUnavailable(orderId, missing); return }

    val reservationId = UUID.randomUUID()
    val ttlDays = config.getInt("reservation.ttl.days", 7).toLong()
    val reservation = Reservation(id = reservationId, orderId = orderId,
        lines = required.map { ReservationLine(it.partId, it.quantity) },
        expiresAt = Instant.now().plus(Duration.ofDays(ttlDays)))
    val execution = Execution(orderId = orderId, status = ExecutionStatus.RESERVED,
        reservationId = reservationId, orderSnapshot = mapper.valueToTree(order), paymentId = null)
    val partsReserved = EventEnvelope(eventType = SagaEventType.PARTS_RESERVED,
        payload = buildPartsReservedPayload(order, reservationId))

    val result = writer.writeAll(
        puts = listOf(reservationRepository.putItem(reservation), executionRepository.putItem(execution), outbox.putItem(partsReserved)),
        decrements = required.map { PartDecrement(it.partId, it.quantity) },
    )
    when (result) {
        TxResult.SUCCESS -> {}
        TxResult.DUPLICATE -> logger.info("OrderCreated $orderId already reserved (idempotent no-op)")
        TxResult.STOCK_CONFLICT -> emitPartsUnavailable(orderId, recomputeMissing(required))
    }
}
```

`buildPartsReservedPayload` monta exatamente o payload do contrato (`orderId, reservationId, customer, services[{name,price}], parts[{id,name,quantity,unitPrice}], totalAmount`). O item Execution recebe `attribute_not_exists(pk)` **dentro** do `writeAll` (o `putItem` do execution inclui essa condição), garantindo idempotência: OrderCreated reentregue → `TransactionCanceledException` na condição do ORDER# → `DUPLICATE`.

- [ ] **Step 4: `DynamoTransactionalWriter`** — monta `TransactWriteItemsRequest` com um `Put` por item de `puts` e um `Update` condicional por `PartDecrement`:

```kotlin
TransactWriteItem {
    update = Update {
        tableName = db.tableName
        key = mapOf("pk" to s(Keys.part(dec.partId)), "sk" to s(Keys.part(dec.partId)))
        updateExpression = "SET quantityInStock = quantityInStock - :q, version = version + :one"
        conditionExpression = "quantityInStock >= :q"
        expressionAttributeValues = mapOf(":q" to n(dec.quantity), ":one" to n(1))
    }
}
```

O `Put` do Execution inclui `conditionExpression = "attribute_not_exists(pk)"`. Capturar `TransactionCanceledException`: se alguma `cancellationReasons[i].code == "ConditionalCheckFailed"` na posição do Execution → DUPLICATE; se numa posição de decremento → STOCK_CONFLICT.

- [ ] **Step 5: `OrderCreatedHandler`** — deserializa `env.payload` → `OrderCreatedPayload` → `reserveParts.reserve(...)`.

- [ ] **Step 6: Rodar testes** — `./gradlew :domain:test --tests '*ReserveParts*'` → PASS. **Step 7: Commit** — `git add -A && git commit -m "feat: reserve parts atomically and emit PartsReserved/PartsUnavailable"`

---

## Task 12: PaymentConfirmed (→ ExecutionStarted) + compensações (liberar reserva)

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/execution/ReleaseReservationUseCase.kt`
- Create: `domain/src/main/kotlin/br/com/soat/execution/handler/{PaymentConfirmedHandler,QuoteRejectedHandler,PaymentFailedHandler}.kt`
- Test: `domain/src/test/kotlin/br/com/soat/execution/{PaymentConfirmedHandlerTest,ReleaseReservationUseCaseTest}.kt`

**Interfaces:**
- Produces:
  - `class ReleaseReservationUseCase(reservationRepository, partRepository, executionRepository, outbox, writer, mapper)` com `fun release(reservationId: UUID, emit: (Execution) -> EventEnvelope?)` — restaura estoque das `lines` (increment), desativa a Reservation, cancela a Execution, e opcionalmente grava um evento no outbox — tudo num `TransactWriteItems`. Idempotente: se a Reservation não está ACTIVE, no-op.
  - `PaymentConfirmedHandler(...) : SagaEventHandler` (`eventType=PaymentConfirmed`): marca Execution paga, `queue()`+`start()`, grava outbox `ExecutionStarted{orderId}`.
  - `QuoteRejectedHandler` / `PaymentFailedHandler` (`eventType=QuoteRejected`/`PaymentFailed`): extraem `reservationId` do payload e chamam `release` (sem evento de saída — order já reage ao próprio QuoteRejected/PaymentFailed; execution só libera). Registrar cancelamento da Execution.

- [ ] **Step 1: Teste `PaymentConfirmedHandler`** — dado Execution RESERVED, ao processar `PaymentConfirmed{orderId,paymentId,amount}` → Execution vira IN_PROGRESS com paymentId e grava outbox `ExecutionStarted`.

- [ ] **Step 2: Teste `ReleaseReservationUseCase`** — Reservation ACTIVE com lines {partId:2} → `release` faz increment +2 no Part, desativa reservation, cancela execution; segunda chamada (reservation já RELEASED) = no-op.

- [ ] **Step 3: Implementar** os três handlers + o use case. O incremento de estoque na liberação é um `Update` `SET quantityInStock = quantityInStock + :q` (sem condição — devolver estoque nunca falha). A desativação da Reservation e o cancel da Execution usam `attribute` updates; a condição de idempotência é `status = ACTIVE` na Reservation (`conditionExpression`), capturando `TransactionCanceledException` como no-op.

- [ ] **Step 4: Rodar testes** → PASS. **Step 5: Commit** — `git add -A && git commit -m "feat: handle PaymentConfirmed and release reservation on QuoteRejected/PaymentFailed"`

---

## Task 13: Ciclo de execução via REST /v1/executions

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/execution/ExecutionLifecycleUseCase.kt`
- Create: `api/src/main/kotlin/br/com/soat/execution/ExecutionRoutes.kt`, `.../execution/dto/{ExecutionResponseDTO,FailExecutionRequestDTO}.kt`
- Test: `domain/src/test/kotlin/br/com/soat/execution/ExecutionLifecycleUseCaseTest.kt`

**Interfaces:**
- Produces:
  - `class ExecutionLifecycleUseCase(executionRepository, outbox, writer, mapper)` com:
    - `fun finishDiagnosis(orderId): Execution` → transição `finishDiagnosis()`, outbox `DiagnoseFinished{orderId}`.
    - `fun finish(orderId): Execution` → `finish()`, outbox `ExecutionFinished{orderId}`.
    - `fun fail(orderId, reason): Execution` → `fail()`, outbox `ExecutionFailed{orderId, paymentId, reason}` (paymentId vem da Execution).
    - `fun listByStatus(status): List<Execution>`, `fun get(orderId): Execution`.
  - Rotas (JWT, ver contrato de gateway `ANY /v1/executions/{proxy+}`):
    - `GET /v1/executions?status=QUEUED` → fila do mecânico.
    - `GET /v1/executions/{orderId}`.
    - `POST /v1/executions/{orderId}/finish-diagnosis` → DiagnoseFinished.
    - `POST /v1/executions/{orderId}/finish` → ExecutionFinished.
    - `POST /v1/executions/{orderId}/fail` (body `{reason}`) → ExecutionFailed.
  - `ExecutionStarted` **não** tem rota (é automático no PaymentConfirmed, Task 12).

- [ ] **Step 1: Teste do use case** — `finish` numa Execution DIAGNOSED grava outbox `ExecutionFinished` e status COMPLETED; `finish` numa RESERVED lança `InvalidExecutionTransitionException` (→ vira 409 na rota).

- [ ] **Step 2: `ExecutionLifecycleUseCase`** — cada método: `findByOrderId` (404 se ausente), aplica transição do modelo (Task 9), grava Execution + outbox event num `TransactWriteItems` (via `writer.writeAll` só com puts, sem decrements).

- [ ] **Step 3: `ExecutionRoutes` + DTOs** — seguir o padrão de `../auto-repair-shop/api/src/main/kotlin/br/com/soat/order/OrderRoutes.kt` (extração de path param, `call.respond`, mapeamento de exceção via `ErrorHandlingConfiguration`). Registrar em `RoutingConfiguration`. `InvalidExecutionTransitionException` → 409 no `ErrorHandlingConfiguration`.

- [ ] **Step 4: Rodar testes** → PASS. **Step 5: Commit** — `git add -A && git commit -m "feat: add execution lifecycle REST endpoints and events"`

---

## Task 14: Job ReservationExpired

**Files:**
- Create: `domain/src/main/kotlin/br/com/soat/execution/ExpireReservationsUseCase.kt`
- Create: `worker/src/main/kotlin/br/com/soat/scheduler/task/ReservationExpiredTask.kt`
- Test: `domain/src/test/kotlin/br/com/soat/execution/ExpireReservationsUseCaseTest.kt`

**Interfaces:**
- Consumes: `ReservationRepository`, `ReleaseReservationUseCase` (Task 12).
- Produces:
  - `class ExpireReservationsUseCase(reservationRepository, release, clock)` com `fun run()` — `findActiveExpiredBefore(now, 25)` → para cada, `release(reservationId) { execution -> EventEnvelope(ReservationExpired, {orderId, reservationId}) }`.
  - `class ReservationExpiredTask(useCase) : ScheduledTask` (`getName()="reservation-expired"`, intervalo config `reservation.job.interval.seconds`, default 3600).

- [ ] **Step 1: Teste** — duas reservations ACTIVE vencidas → `run()` chama `release` 2x e emite `ReservationExpired` para cada.

- [ ] **Step 2: `ExpireReservationsUseCase`** — reusa `ReleaseReservationUseCase.release` passando um `emit` que devolve o envelope `ReservationExpired{orderId, reservationId}`. Assim o estoque volta, a reserva expira e o evento sai — atômico e idempotente (se o job rodar 2x na mesma reserva, a 2ª é no-op pela condição `status=ACTIVE`).

- [ ] **Step 3: `ReservationExpiredTask`** — delega ao use case.

- [ ] **Step 4: Rodar testes** → PASS. **Step 5: Commit** — `git add -A && git commit -m "feat: add reservation expiry job emitting ReservationExpired"`

---

## Task 15: Wiring completo (Main/Koin) + testes de integração da saga

**Files:**
- Modify: `main/src/main/kotlin/br/com/soat/Main.kt` (Koin completo + start dos workers)
- Create: `main/src/test/kotlin/br/com/soat/IntegrationTest.kt` (harness LocalStack DynamoDB+SNS+SQS), `.../IntegrationTestHttpClient.kt`, `.../SagaFixtures.kt`
- Create: `main/src/test/kotlin/br/com/soat/saga/SagaFlowIntegrationTest.kt`, `.../part/PartIntegrationTest.kt`, `.../execution/ExecutionLifecycleIntegrationTest.kt`

**Interfaces:**
- Consumes: tudo das Tasks 3–14.
- Produces: `applicationModule` Koin completo; app que consome a fila e produz eventos; suíte de integração cobrindo os fluxos.

- [ ] **Step 1: Koin `applicationModule`** — registrar (baseado no `Main.kt` do monólito, adaptado):

```kotlin
val applicationModule = module {
    single<Config> { Config.fromClasspath("application.yaml") }
    single<CoroutineDispatcher> { Dispatchers.IO }
    single<ObjectMapper> { jacksonObjectMapper().registerModule(JavaTimeModule()) }
    // observability (clone)
    single<PrometheusMeterRegistry> { prometheusMeterRegistry() }
    single<MeterRegistry> { get<PrometheusMeterRegistry>() }
    single<MetricsPort> { MicrometerMetricsPort(get()) }
    // dynamodb
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
    // messaging out
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
    single<PartRepository> { PartDynamoRepository(get()) }
    single<ExecutionRepository> { ExecutionDynamoRepository(get(), get()) }
    single<ReservationRepository> { ReservationDynamoRepository(get()) }
    single<OutboxRepository> { OutboxDynamoRepository(get(), get()) }
    single<ProcessedEventRepository> { ProcessedEventDynamoRepository(get()) }
    single<TransactionalWriter> { DynamoTransactionalWriter(get()) }
    // domain
    single { PartUseCase(get()) }
    single { ReservePartsUseCase(get(), get(), get(), get(), get(), get(), get()) }
    single { ReleaseReservationUseCase(get(), get(), get(), get(), get(), get()) }
    single { ExecutionLifecycleUseCase(get(), get(), get(), get()) }
    single { ExpireReservationsUseCase(get(), get(), get()) }
    // saga handlers
    single { OrderCreatedHandler(get(), get()) } bind SagaEventHandler::class
    single { PaymentConfirmedHandler(get(), get()) } bind SagaEventHandler::class
    single { QuoteRejectedHandler(get(), get()) } bind SagaEventHandler::class
    single { PaymentFailedHandler(get(), get()) } bind SagaEventHandler::class
    single { SagaDispatcher(getAll(), get()) }
    // workers
    single {
        val c = get<Config>()
        SqsConsumerWorker(
            queueUrl = c.getString("sqs.queue.url"),
            region = c.getStringOrNull("aws.region") ?: "us-east-1",
            endpointOverride = c.getStringOrNull("aws.endpoint"),
            accessKeyId = c.getStringOrNull("aws.accessKeyId"),
            secretAccessKey = c.getStringOrNull("aws.secretAccessKey"),
            dispatcher = get(), mapper = get(), coroutineDispatcher = get(),
        )
    }
    // scheduled tasks
    single { OutboxRelayTask(get(), get(), get()) } bind ScheduledTask::class
    single { ReservationExpiredTask(get()) } bind ScheduledTask::class
    single { ScheduledTaskRunner(getAll()) }
}
```

`main()` passa a startar: `koin.get<SqsConsumerWorker>().start()` e `koin.get<ScheduledTaskRunner>().start()` antes do `KtorHttpServer`.

- [ ] **Step 2: Harness `IntegrationTest.kt`** — adaptar de `../auto-repair-shop/main/src/test/kotlin/br/com/soat/IntegrationTest.kt`:
  - `LocalStackContainer(...).withServices(DYNAMODB, SNS, SQS)`.
  - No `setup()`: subir localstack; criar tabela (`db.createExecutionTable()` da Task 4); criar o tópico `execution-events` e a fila `execution-saga` **assinando** o tópico com raw delivery; injetar no `Config` `dynamodb.table.name`, `sns.topic.arn`, `sqs.queue.url`, `aws.endpoint/region/accessKeyId/secretAccessKey`.
  - `@BeforeEach cleanTable()`: deletar+recriar a tabela (ou scan+delete) e purgar a fila.
  - Método `sendToQueue(envelope)`: publica um envelope na fila de entrada do execution (simula os outros serviços). Método `waitForPublishedEvent(eventType)`: cria uma fila de "espião" assinando o tópico `execution-events` no setup e faz long-poll (igual `waitForSnsMessage` do monólito, mas casando por `messageAttributes.eventType`).

- [ ] **Step 3: `SagaFlowIntegrationTest`** — cenários (cada um envia envelope na fila e espera o publicado):
  1. **Fluxo feliz da reserva**: seed de Parts com estoque → envia `OrderCreated` (fat) → espera `PartsReserved` publicado com `reservationId` + quote priced repassado; estoque decrementado no DynamoDB.
  2. **Estoque insuficiente**: seed com estoque baixo → `OrderCreated` → espera `PartsUnavailable` com `missingParts`; estoque inalterado.
  3. **Idempotência**: enviar o mesmo `OrderCreated` 2x → só um `PartsReserved`, estoque decrementado só uma vez.
  4. **PaymentConfirmed → ExecutionStarted**: após reserva, enviar `PaymentConfirmed` → espera `ExecutionStarted`; Execution IN_PROGRESS.
  5. **Compensação**: após reserva, enviar `QuoteRejected{reservationId}` → estoque restaurado, Reservation RELEASED, Execution CANCELED.
  6. **PaymentFailed**: idem, restaura estoque.

- [ ] **Step 4: `ExecutionLifecycleIntegrationTest`** — via REST: após PaymentConfirmed, `POST /finish-diagnosis` → espera `DiagnoseFinished`; `POST /finish` → `ExecutionFinished`; `POST /fail` → `ExecutionFailed`.

- [ ] **Step 5: `PartIntegrationTest`** — CRUD REST de `/v1/parts`.

- [ ] **Step 6: Rodar tudo** — `./gradlew test integrationTest jacocoAggregatedReport` → PASS e cobertura ≥80%. Se abaixo, adicionar testes unitários nos pontos descobertos (handlers, writer, máquina de estados).

- [ ] **Step 7: Commit** — `git add -A && git commit -m "feat: wire application and add saga integration tests"`

---

## Task 16: Kubernetes (manifests base + overlays) e Dockerfile

**Files:**
- Create: `infra/k8s/base/{kustomization,deployment,service,configmap,hpa,servicemonitor}.yaml`
- Create: `infra/k8s/overlays/hml/{kustomization,deployment-patch,configmap-patch}.yaml`, `infra/k8s/overlays/prod/{...}.yaml`

**Interfaces:**
- Produces: overlays `hml`/`prod` que o `kubectl apply -k` sobe. Service NodePort **30082**, imagem `auto-repair-shop-execution`, namespace `auto-repair-shop-{env}`.

- [ ] **Step 1: Clonar `infra/k8s/` do monólito** e substituir em todos os arquivos:
  - nome/labels `auto-repair-shop` → `auto-repair-shop-execution` (Deployment, Service, HPA, ServiceMonitor, ConfigMap, selectors).
  - `service.yaml`: `nodePort: 30080` → **`30082`**.
  - `deployment.yaml`: `image: ghcr.io/ivanzao/auto-repair-shop-execution:latest`; **remover** o `secretRef: auto-repair-shop-secret` do `envFrom` (execution não tem secret) — deixar só o `configMapRef`.
  - overlays: `images[].name` → `ghcr.io/ivanzao/auto-repair-shop-execution`; namespaces `auto-repair-shop-{hml,prod}`.

- [ ] **Step 2: `configmap.yaml` base** — chaves que o app lê (sobrescritas em runtime pelo CI a partir do SSM):

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: auto-repair-shop-execution-config
data:
  SERVER_PORT: "8080"
  AWS_REGION: "us-east-1"
  # DYNAMODB_TABLE_NAME, SNS_TOPIC_ARN, SQS_QUEUE_URL injetados no deploy (vêm de SSM)
```

- [ ] **Step 3: Validar kustomize** — `kubectl kustomize infra/k8s/overlays/hml` e `.../prod` renderizam sem erro.

- [ ] **Step 4: Commit** — `git add -A && git commit -m "feat: add kubernetes manifests for execution service"`

---

## Task 17: CI/CD (pr-check + build-and-deploy), README e proteção de branch

**Files:**
- Create: `.github/workflows/pr-check.yaml`, `.github/workflows/build-and-deploy.yaml`
- Create: `README.md`

**Interfaces:**
- Produces: pipelines que testam, empacotam (GHCR), e aplicam no EKS lendo SSM.

- [ ] **Step 1: `pr-check.yaml`** — clonar de `../auto-repair-shop/.github/workflows/pr-check.yaml` e **acrescentar** o passo Sonar após os testes (a spec exige SonarCloud no pr-check):

```yaml
      - name: Sonar
        env:
          SONAR_TOKEN: ${{ secrets.SONAR_TOKEN }}
          SONAR_HOST_URL: https://sonarcloud.io
        run: ./gradlew jacocoAggregatedReport sonar
```

(garantir `fetch-depth: 0` no checkout para o Sonar.)

- [ ] **Step 2: `build-and-deploy.yaml`** — clonar de `../auto-repair-shop/.github/workflows/build-and-deploy.yaml` e adaptar o job `deploy`:
  - `IMAGE_NAME` continua `${{ github.repository }}` (já é `auto-repair-shop-execution`).
  - **Remover** os passos de Secrets Manager/DB (`Read DB credentials`, `Create K8s Secret with DB password`) — execution não tem DB nem secret.
  - Passo "Read SSM params": ler os params do execution:

```yaml
      - name: Read SSM params
        id: ssm
        run: |
          CLUSTER=$(aws ssm get-parameter --name /auto-repair-shop/${{ env.ENV }}/eks/cluster-name --query Parameter.Value --output text)
          TABLE=$(aws ssm get-parameter --name /auto-repair-shop/${{ env.ENV }}/execution/dynamodb/table-name --query Parameter.Value --output text)
          SNS_TOPIC=$(aws ssm get-parameter --name /auto-repair-shop/${{ env.ENV }}/sns/execution-events-topic-arn --query Parameter.Value --output text)
          SQS_URL=$(aws ssm get-parameter --name /auto-repair-shop/${{ env.ENV }}/sqs/execution-saga-queue-url --query Parameter.Value --output text)
          APIGW=$(aws ssm get-parameter --name /auto-repair-shop/${{ env.ENV }}/apigw/endpoint --query Parameter.Value --output text)
          {
            echo "cluster=$CLUSTER"; echo "table=$TABLE"; echo "sns_topic=$SNS_TOPIC"
            echo "sqs_url=$SQS_URL"; echo "apigw_endpoint=$APIGW"
          } >> "$GITHUB_OUTPUT"
```

  - Passo "Rewrite ConfigMap overlay":

```yaml
      - name: Rewrite ConfigMap overlay with runtime values
        working-directory: infra/k8s/overlays/${{ env.ENV }}
        run: |
          cat > configmap-patch.yaml <<EOF
          apiVersion: v1
          kind: ConfigMap
          metadata:
            name: auto-repair-shop-execution-config
          data:
            APPLICATION_PROFILE: "${{ env.ENV }}"
            AWS_REGION: "us-east-1"
            DYNAMODB_TABLE_NAME: "${{ steps.ssm.outputs.table }}"
            SNS_TOPIC_ARN: "${{ steps.ssm.outputs.sns_topic }}"
            SQS_QUEUE_URL: "${{ steps.ssm.outputs.sqs_url }}"
          EOF
```

  - `kustomize edit set image` → `ghcr.io/ivanzao/auto-repair-shop-execution=...`; rollout `deployment/auto-repair-shop-execution`; smoke test `GET {apigw}/v1/parts` (com um JWT de teste) ou `/health` do NodePort — usar `/health` interno via `kubectl` port-forward é mais simples; manter o smoke test do monólito apontando para uma rota pública de health se existir, senão só o `rollout status`.

- [ ] **Step 3: `README.md`** — documentar: arquitetura do execution, **justificativa da coreografia** (sem orquestrador; estado derivado por serviço), modelo single-table (a tabela da seção "Estrutura de arquivos"), o contrato SSM consumido, a decisão de **não** usar ShedLock (idempotência cobre multi-réplica), e como rodar testes/local. Incluir o diagrama de fluxo da saga (copiar do contrato).

- [ ] **Step 4: Verificar workflows localmente** — `yamllint .github/workflows/*.yaml` (ou revisão visual); rodar `./gradlew build integrationTest` uma última vez.

- [ ] **Step 5: Commit + push + proteção de branch**

```bash
git add -A && git commit -m "ci: add pr-check and build-and-deploy workflows and README"
git branch -M main
gh repo create ivanzao/auto-repair-shop-execution --private --source=. --remote=origin --push
gh api -X PUT repos/ivanzao/auto-repair-shop-execution/branches/main/protection \
  -f 'required_status_checks[strict]=true' -f 'required_pull_request_reviews[required_approving_review_count]=1' \
  -F 'enforce_admins=true' -F 'restrictions=null' 2>/dev/null || echo "Ajustar proteção via UI se a API falhar"
```

> Nota: o secret `SONAR_TOKEN` e os `AWS_*`/`GHCR_*` do repo precisam ser criados no GitHub (`gh secret set ...`) — mesmos nomes usados nos workflows do monólito.

---

## Self-Review

**Cobertura da spec/escopo (do pedido do usuário e do contrato):**
- Bootstrap do repo → Tasks 1–2. ✔
- Consumir `OrderCreated` → Task 8 (consumer) + Task 11 (handler). ✔
- Reservar peças (atômico, condicional) → Task 11. ✔
- Produzir `PartsReserved` repassando o quote priced → Task 11 (`buildPartsReservedPayload`). ✔
- Eventos de execução (`ExecutionStarted` auto, `DiagnoseFinished`/`ExecutionFinished`/`ExecutionFailed` via REST) → Tasks 12–13. ✔
- Compensações (`QuoteRejected`/`PaymentFailed` liberam reserva; `PartsUnavailable`) → Tasks 11–12. ✔
- Job `ReservationExpired` → Task 14. ✔
- DynamoDB single-table → Tasks 3–6, 9–10. ✔
- Deploy completo (Docker, K8s NodePort 30082, CI pr-check+deploy, branch protection) → Tasks 16–17. ✔
- Testes ≥80% + Sonar, sem BDD → Task 15 + Task 17. ✔
- Idempotência + envelope camelCase `eventType` → Tasks 5, 6, 7, 8. ✔

**Consistência de tipos:** `EventEnvelope`, `SagaEventHandler.eventType: String`, `SagaEventType.*` constantes, `TransactionalWriter.writeAll(puts, decrements) → TxResult`, `OutboxRepository.putItem/pending/markPublished/save`, `ExecutionStatus` e transições — nomes usados de forma idêntica entre as tasks que os definem (6/8/9/11) e as que os consomem (11–15).

**Riscos sinalizados para a execução:**
- (a) API do AWS SDK Kotlin (`TransactWriteItem`, `cancellationReasons`, `AttributeValue.L/M`) — confirmar shape exato na versão 1.5.13 ao implementar a Task 11; ajustar imports se necessário.
- (b) `mapper.readValue(msg.body, EventEnvelope::class.java)` com `payload: JsonNode` — garantir que o `ObjectMapper` desserializa o campo `payload` como árvore (é o comportamento default do Jackson para `JsonNode`).
- (c) Smoke test do deploy (Task 17) depende de rota de health acessível — decidir na execução entre `/health` via NodePort/port-forward ou uma rota pública.

---

## Execution Handoff

**Plano completo e salvo em `docs/superpowers/plans/2026-07-18-fase4-plano-4-execution.md`.**
</content>
</invoke>
