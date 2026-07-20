package br.com.soat.shared.dto

data class ValidationErrorDTO(
    val fieldErrors: List<FieldError>
) {
    val code = "BAD_REQUEST"
}

data class FieldError(
    val field: String,
    val message: String
)