package br.com.soat.shared.model

import java.util.UUID

data class User(
    val id: UUID,
    val document: String,
)
