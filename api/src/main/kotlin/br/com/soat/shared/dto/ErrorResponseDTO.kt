package br.com.soat.shared.dto

import br.com.soat.shared.exception.ApplicationException

data class ErrorResponseDTO(
    val code: String,
    val message: String
) {

    companion object {
        fun internalServerError(message: String? = null) = ErrorResponseDTO("INTERNAL_SERVER_ERROR", message ?: "Internal server error")
    }
}

fun ApplicationException.toErrorResponseDTO(customMessage: String? = null) =
    ErrorResponseDTO(
        code = this.errorCode,
        message = customMessage ?: this.errorMessage ?: this.localizedMessage
    )