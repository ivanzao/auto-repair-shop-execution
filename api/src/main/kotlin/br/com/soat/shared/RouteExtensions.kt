package br.com.soat.shared

import br.com.soat.auth.JwtUserPrincipal
import br.com.soat.shared.model.User
import io.ktor.server.auth.principal
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

fun RoutingCall.authenticatedUser(): User {
    val principal = principal<JwtUserPrincipal>()
        ?: throw IllegalStateException("Authenticated principal is missing")
    return User(id = principal.userId, document = principal.document)
}
