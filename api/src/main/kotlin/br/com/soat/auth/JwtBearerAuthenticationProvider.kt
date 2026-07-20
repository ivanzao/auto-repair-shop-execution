package br.com.soat.auth

import io.ktor.http.HttpStatusCode
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.auth.AuthenticationConfig
import io.ktor.server.auth.AuthenticationContext
import io.ktor.server.auth.AuthenticationFailedCause
import io.ktor.server.auth.AuthenticationProvider
import io.ktor.server.auth.parseAuthorizationHeader
import io.ktor.server.response.respond
import java.util.UUID

data class JwtUserPrincipal(val userId: UUID, val role: String)

class JwtBearerAuthenticationProvider(
    config: Config,
) : AuthenticationProvider(config) {

    private val allowedRoles: Set<String> = config.allowedRoles

    class Config(name: String?) : AuthenticationProvider.Config(name) {
        var allowedRoles: Set<String> = emptySet()
    }

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val header = call.request.parseAuthorizationHeader()
        val token = (header as? HttpAuthHeader.Single)
            ?.takeIf { it.authScheme.equals("Bearer", ignoreCase = true) }
            ?.blob

        if (token.isNullOrBlank()) {
            context.challenge("JwtBearer", AuthenticationFailedCause.NoCredentials) { challenge, c ->
                c.respond(HttpStatusCode.Unauthorized)
                challenge.complete()
            }
            return
        }

        val claims = JwtClaims.parse(token)
        if (claims == null) {
            context.challenge("JwtBearer", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                c.respond(HttpStatusCode.Unauthorized)
                challenge.complete()
            }
            return
        }

        val userId = try {
            UUID.fromString(claims.sub)
        } catch (e: IllegalArgumentException) {
            context.challenge("JwtBearer", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                c.respond(HttpStatusCode.Unauthorized)
                challenge.complete()
            }
            return
        }

        if (allowedRoles.isNotEmpty() && claims.role !in allowedRoles) {
            context.challenge("JwtBearer", AuthenticationFailedCause.InvalidCredentials) { challenge, c ->
                c.respond(HttpStatusCode.Forbidden)
                challenge.complete()
            }
            return
        }

        context.principal(JwtUserPrincipal(userId, claims.role))
    }
}

fun AuthenticationConfig.jwtBearer(
    name: String? = null,
    configure: JwtBearerAuthenticationProvider.Config.() -> Unit = {},
) {
    val provider = JwtBearerAuthenticationProvider(
        JwtBearerAuthenticationProvider.Config(name).apply(configure),
    )
    register(provider)
}
