package br.com.soat.auth

import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class JwtClaimsTest {

    private fun b64u(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())

    private fun token(payload: String): String = "${b64u("""{"alg":"none","typ":"JWT"}""")}.${b64u(payload)}.sig"

    @Test
    fun `parses sub role and cpf`() {
        val claims = JwtClaims.parse(
            token("""{"sub":"00000000-0000-0000-0000-000000000003","role":"MECHANIC","cpf":"12345678909"}"""),
        )

        assertEquals("00000000-0000-0000-0000-000000000003", claims?.sub)
        assertEquals("MECHANIC", claims?.role)
        assertEquals("12345678909", claims?.cpf)
    }

    @Test
    fun `rejects a token without cpf`() {
        assertNull(JwtClaims.parse(token("""{"sub":"00000000-0000-0000-0000-000000000003","role":"MECHANIC"}""")))
    }

    @Test
    fun `rejects a token without role`() {
        assertNull(JwtClaims.parse(token("""{"sub":"00000000-0000-0000-0000-000000000003","cpf":"12345678909"}""")))
    }

    @Test
    fun `rejects a malformed token`() {
        assertNull(JwtClaims.parse("not-a-jwt"))
    }
}
