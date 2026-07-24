package br.com.soat.event

import java.util.UUID

interface ProcessedEventRepository {
    fun markProcessed(eventId: UUID, consumerId: String): Boolean
    fun isProcessed(eventId: UUID, consumerId: String): Boolean
}
