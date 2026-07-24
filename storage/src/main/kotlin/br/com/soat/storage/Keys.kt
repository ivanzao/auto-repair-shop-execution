package br.com.soat.storage

import java.util.UUID

object Keys {
    const val GSI = "gsi1"
    const val SUPPLY_LIST = "SUPPLY"
    const val OUTBOX_PENDING = "OUTBOX#PENDING"
    const val RES_ACTIVE = "RES#ACTIVE"

    fun supply(id: UUID) = "SUPPLY#$id"
    fun order(id: UUID) = "ORDER#$id"
    fun reservation(id: UUID) = "RES#$id"
    fun outbox(eventId: UUID) = "OUTBOX#$eventId"
    fun processed(eventId: UUID) = "PROC#$eventId"
    fun consumer(consumerId: String) = "CONS#$consumerId"
    fun execStatus(status: String) = "EXEC#$status"
}
