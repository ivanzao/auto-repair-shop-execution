package br.com.soat.event

import java.util.UUID

/**
 * Dedup de eventos consumidos por `(eventId, consumerId)`.
 * `markProcessed` retorna true se gravou agora (primeira vez), false se já existia — é o guard de idempotência.
 */
interface ProcessedEventRepository {
    fun markProcessed(eventId: UUID, consumerId: String): Boolean
    fun isProcessed(eventId: UUID, consumerId: String): Boolean
}
