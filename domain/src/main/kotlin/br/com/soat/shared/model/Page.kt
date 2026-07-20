package br.com.soat.shared.model

data class Page<T>(
    val content: List<T>,
    val page: Int,
    val size: Int
)
