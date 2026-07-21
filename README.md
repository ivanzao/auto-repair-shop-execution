# auto-repair-shop-execution

Microsserviço **execution** da oficina (Fase 4). Consome `OrderCreated`, reserva insumos de forma
atômica, produz `SuppliesReserved` (repassando o quote priced), conduz o ciclo de execução da OS e
executa as compensações da saga. Kotlin/Ktor sobre **DynamoDB single-table**, coreografia pura (sem
orquestrador).

## Arquitetura

Esqueleto hexagonal multi-módulo (clonado do monólito `auto-repair-shop`), trocando o storage
Postgres/Exposed por **DynamoDB single-table** e adicionando a metade **consumidora** da saga.

```
domain/   modelos, use cases, portas (repositories), eventos e envelope
api/      Ktor: /v1/supplies, /v1/executions, /health, /metrics
storage/  DynamoDB: provider, key helpers, *DynamoRepository, outbox, idempotência, TransactionalWriter
worker/   SnsPublisher, SqsConsumerWorker + SagaDispatcher, ScheduledTaskRunner, OutboxRelayTask, ReservationExpiredTask
metric/   MicrometerMetricsPort
main/     Main.kt (wiring Koin), application.yaml
```

### Coreografia (sem orquestrador)

Cada serviço reage a eventos e deriva seu próprio estado — não há um coordenador central. O execution:

```
                 OrderCreated (order)
                        │
                        ▼
          ┌─────────────────────────────┐
          │  reserva atômica de insumos  │
          └─────────────────────────────┘
             │                      │
   estoque ok│                      │estoque insuficiente / corrida
             ▼                      ▼
     SuppliesReserved         PartsUnavailable
       (→ billing)              (→ order)
             │
   PaymentConfirmed (billing) ──► ExecutionStarted (→ order)
             │
   REST mecânico: finish-diagnosis ─► DiagnoseFinished
                  finish           ─► ExecutionFinished  (→ order)
                  fail             ─► ExecutionFailed     (→ order, billing refund)

   Compensações que liberam a reserva (devolvem estoque, cancelam a Execution):
     QuoteRejected (billing) · PaymentFailed (billing) · ReservationExpired (job)
```

Justificativa: cada evento é um fato imutável; o estado da OS no execution é derivado da sequência de
eventos recebidos + suas próprias transições. Isso remove acoplamento temporal e ponto único de
coordenação, ao custo de idempotência e compensações explícitas (tratadas abaixo).

## Modelo DynamoDB single-table

Tabela `auto-repair-shop-execution-{env}`, chaves `pk`/`sk` (S), GSI `gsi1` (`gsi1pk`/`gsi1sk`, projection ALL).

| Item | pk / sk | gsi1pk / gsi1sk (esparso) | Uso |
|---|---|---|---|
| Supply | `SUPPLY#{id}` | `SUPPLY` / `{name}` | estoque; listagem `/v1/supplies` |
| Execution | `ORDER#{orderId}` | `EXEC#{status}` / `{createdAt}` | agregado da OS; fila do mecânico |
| Reservation | `RES#{id}` | `RES#ACTIVE` / `{expiresAt}` | linhas reservadas; job de expiração |
| Outbox | `OUTBOX#{eventId}` | `OUTBOX#PENDING` / `{occurredAt}` | relay → SNS |
| ProcessedEvent | `PROC#{eventId}` / `CONS#{consumerId}` | — | dedup |

O GSI é **esparso**: só a `Reservation` ACTIVE e o `Outbox` PENDING carregam `gsi1pk`, então os
respectivos jobs (expiração / relay) varrem apenas o que interessa.

## Atomicidade, idempotência e multi-réplica

- **Reserva atômica**: `TransactWriteItems` grava Reservation + Execution + evento de outbox e
  decrementa o estoque com condição `quantityInStock >= :q`. Falha condicional num decremento →
  `PartsUnavailable`; falha no put de idempotência (`attribute_not_exists(pk)` na Execution) → no-op.
- **Idempotência de consumo**: dedup por `(eventId, consumerId)` (item `PROC#…`, conditional put) +
  escrita condicional em toda mutação de saga. OrderCreated reentregue → `DUPLICATE`, sem efeito.
- **Sem ShedLock**: o monólito usava ShedLock (JDBC) para serializar jobs entre réplicas. Aqui **não**
  há JDBC e as escritas são idempotentes/condicionais, então múltiplas réplicas são seguras sem lock
  distribuído. Se duas réplicas relayarem o mesmo item do outbox, ambas publicam mas o downstream
  deduplica por `eventId`.
- **Outbox transacional → relay → SNS**: o evento é gravado na mesma transação da mutação; o
  `OutboxRelayTask` (a cada 5s) publica os pendentes no SNS e remove do índice PENDING.

## Contrato de eventos

Consome (fila `execution-saga`): `OrderCreated`, `PaymentConfirmed`, `QuoteRejected`, `PaymentFailed`.
Produz (tópico `execution-events`, attribute `eventType` camelCase): `SuppliesReserved`,
`PartsUnavailable`, `ExecutionStarted`, `DiagnoseFinished`, `ExecutionFinished`, `ExecutionFailed`,
`ReservationExpired`. Envelope: `{ eventId, eventType, eventVersion, occurredAt, payload }`.

`SuppliesReserved` (consumido pelo billing) enriquece `name`/`unitPrice` de cada insumo a partir do
estoque local e calcula `totalAmount`:

```json
{
  "orderId": "uuid", "reservationId": "uuid",
  "customer": { "name": "...", "email": "..." },
  "services": [ { "name": "...", "price": 149.90 } ],
  "supplies": [ { "id": "uuid", "name": "...", "quantity": 2, "unitPrice": 30.00 } ],
  "totalAmount": 209.90
}
```

## Configuração (env)

| Env | Default (dev) | Origem em runtime (SSM) |
|---|---|---|
| `DYNAMODB_TABLE_NAME` | auto-repair-shop-execution-dev | `/auto-repair-shop/{env}/execution/dynamodb/table-name` |
| `SNS_TOPIC_ARN` | (local) | `/auto-repair-shop/{env}/sns/execution-events-topic-arn` |
| `SQS_QUEUE_URL` | (local) | `/auto-repair-shop/{env}/sqs/execution-saga-queue-url` |
| `AWS_REGION` | us-east-1 | — |
| `RESERVATION_TTL_DAYS` | 7 | — |

Em hml/prod as credenciais AWS vêm da cadeia default do SDK (node role LabRole, ADR-002 — sem IAM
role/policy dedicada, sem Secrets Manager). O execution só lê SSM.

## Rodando

```bash
./gradlew build                 # compila + testes unitários
./gradlew test                  # unitários
./gradlew integrationTest       # integração (LocalStack via TestContainers — requer Docker)
./gradlew jacocoAggregatedReport
./gradlew :main:run             # sobe local em :8080 (aponta para LocalStack/local)
```

Health/metrics: `GET /health`, `GET /metrics`. Deploy: containerPort 8080, Service NodePort **30082**,
imagem `ghcr.io/ivanzao/auto-repair-shop-execution`, namespace `auto-repair-shop-{env}`.

## API

Swagger UI em `GET /swagger`; spec em `api/src/main/resources/openapi/documentation.yaml`.
Rotas de `/v1/executions` exigem role `MECHANIC`; as de `/v1/supplies`, `ADMIN`.

## Cobertura

| Métrica | Valor |
|---|---|
| Cobertura (SonarCloud) | **89.0%** |
| Testes | 31 |
| Quality gate | Passed |

Análise a cada PR pelo step `Sonar` do `pr-check.yaml`, no projeto
`auto-repair-shop-execution` da organização `ivanzao` no SonarCloud. O quality gate
exige 80% de cobertura em código novo.

Ficam fora da contagem de cobertura o wiring de framework (`config`, `auth`,
`metric`), o módulo `main` e os DTOs — código sem lógica de negócio própria. Eles
seguem analisados para bugs, code smells e security hotspots.

<!-- TODO: print do dashboard do SonarCloud (projeto é privado, link exige login) -->
