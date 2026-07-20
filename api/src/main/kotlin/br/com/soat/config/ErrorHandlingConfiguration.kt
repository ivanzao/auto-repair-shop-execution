package br.com.soat.config

import br.com.soat.shared.dto.ErrorResponseDTO
import br.com.soat.shared.dto.FieldError
import br.com.soat.shared.dto.ValidationErrorDTO
import br.com.soat.shared.dto.toErrorResponseDTO
import br.com.soat.shared.exception.ApplicationException
import br.com.soat.shared.exception.ConflictException
import br.com.soat.shared.exception.NotFoundException
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.exc.InvalidNullException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.fasterxml.jackson.databind.exc.ValueInstantiationException
import io.ktor.http.HttpStatusCode.Companion.BadRequest
import io.ktor.http.HttpStatusCode.Companion.Conflict
import io.ktor.http.HttpStatusCode.Companion.InternalServerError
import io.ktor.http.HttpStatusCode.Companion.NotFound
import io.ktor.http.HttpStatusCode.Companion.UnprocessableEntity
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import kotlin.text.ifEmpty
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("br.com.soat.config.ErrorHandling")

fun Application.configureErrorHandling() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            val (statusCode, body) = when (cause) {
                is NotFoundException -> NotFound to ErrorResponseDTO("NOT_FOUND", cause.message ?: "Not found")

                is ConflictException -> Conflict to ErrorResponseDTO("CONFLICT", cause.message ?: "Conflict")

                is ApplicationException -> UnprocessableEntity to cause.toErrorResponseDTO()

                is BadRequestException -> {
                    val fieldErrors = extractFieldErrors(cause)
                    BadRequest to ValidationErrorDTO(fieldErrors)
                }

                else -> {
                    val message = "${cause::class.simpleName}: ${cause.message}"
                    InternalServerError to ErrorResponseDTO.internalServerError(message)
                }
            }

            val method = call.request.httpMethod.value
            val path = call.request.path()
            if (statusCode == InternalServerError) {
                logger.error("Unhandled exception method={} path={} status={}", method, path, statusCode.value, cause)
            } else {
                logger.warn(
                    "Request failed method={} path={} status={} exception={} message={}",
                    method, path, statusCode.value, cause::class.simpleName, cause.message,
                )
            }

            call.respond(statusCode, body)
        }
    }
}

private fun findRootCause(throwable: Throwable): Throwable {
    var current: Throwable = throwable
    while (current.cause != null && current.cause != current) {
        current = current.cause!!
    }
    return current
}

private fun extractFieldErrors(cause: BadRequestException): List<FieldError> {
    return when (val rootCause = findRootCause(cause)) {
        is ValueInstantiationException -> {
            val fieldPath = rootCause.path.joinToString(".") { ref ->
                ref.fieldName ?: "[${ref.index}]"
            }
            listOf(FieldError(field = fieldPath.ifEmpty { "unknown" }, message = "Field is required"))
        }
        is InvalidNullException -> {
            val fieldPath = rootCause.path.joinToString(".") { ref ->
                ref.fieldName ?: "[${ref.index}]"
            }
            listOf(FieldError(field = fieldPath.ifEmpty { "unknown" }, message = "Field is required and cannot be null"))
        }
        is MismatchedInputException -> {
            val fieldPath = rootCause.path.joinToString(".") { ref ->
                ref.fieldName ?: "[${ref.index}]"
            }
            val message = when {
                rootCause.targetType?.isEnum == true -> {
                    val enumValues = rootCause.targetType.enumConstants.joinToString(", ")
                    "Invalid value. Accepted values: $enumValues"
                }
                fieldPath.isNotEmpty() -> "Invalid or missing value"
                else -> rootCause.originalMessage ?: "Invalid value"
            }
            if (fieldPath.isNotEmpty()) {
                listOf(FieldError(field = fieldPath, message = message))
            } else {
                listOf(FieldError(field = "request", message = rootCause.originalMessage ?: "Invalid request body"))
            }
        }
        is JsonMappingException -> {
            val fieldPath = rootCause.path.joinToString(".") { ref ->
                ref.fieldName ?: "[${ref.index}]"
            }
            if (fieldPath.isNotEmpty()) {
                listOf(FieldError(field = fieldPath, message = rootCause.originalMessage ?: "Invalid format"))
            } else {
                listOf(FieldError(field = "request", message = rootCause.originalMessage ?: "Invalid request body"))
            }
        }
        else -> {
            val message = rootCause.message ?: cause.message ?: "Invalid request"
            listOf(FieldError(field = "request", message = message))
        }
    }
}
