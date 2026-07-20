package br.com.soat.shared

import io.ktor.server.plugins.BadRequestException
import io.ktor.server.routing.RoutingCall
import java.util.UUID

fun RoutingCall.getUUIDPathParameter(name: String): UUID {
    val value = parameters[name]
        ?: throw BadRequestException("Missing required parameter: $name")

    return try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid UUID format for parameter: $name")
    }
}

fun RoutingCall.getUUIDQueryParameter(name: String): UUID {
    val value = request.queryParameters[name]
        ?: throw BadRequestException("Missing required query parameter: $name")

    return try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Invalid UUID format for query parameter: $name")
    }
}
