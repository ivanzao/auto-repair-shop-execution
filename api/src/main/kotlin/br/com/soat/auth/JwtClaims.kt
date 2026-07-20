package br.com.soat.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.util.Base64

data class JwtClaims(val sub: String, val role: String) {

    companion object {
        private val mapper: ObjectMapper = jacksonObjectMapper()

        fun parse(token: String): JwtClaims? {
            val parts = token.split('.')
            if (parts.size != 3) return null

            val payloadJson = try {
                val bytes = Base64.getUrlDecoder().decode(parts[1])
                String(bytes)
            } catch (e: IllegalArgumentException) {
                return null
            }

            val node = try {
                mapper.readTree(payloadJson)
            } catch (e: Exception) {
                return null
            }

            val sub = node.get("sub")?.asText()?.takeIf { it.isNotBlank() } ?: return null
            val role = node.get("role")?.asText()?.takeIf { it.isNotBlank() } ?: return null

            return JwtClaims(sub, role)
        }
    }
}
