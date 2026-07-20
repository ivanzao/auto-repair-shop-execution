package br.com.soat.shared.dto

import br.com.soat.shared.model.Page

data class PageResponseDTO<T>(
    val content: List<T>,
    val page: Int,
    val size: Int,
) {
    companion object {
        fun <T, R> from(page: Page<T>, mapper: (T) -> R): PageResponseDTO<R> {
            return PageResponseDTO(
                content = page.content.map(mapper),
                page = page.page,
                size = page.size
            )
        }
    }
}