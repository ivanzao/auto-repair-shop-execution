package br.com.soat.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class ConfigFactoryTest {

    private fun load(env: Map<String, String>) =
        Config.fromClasspath("config-interpolation-test.yaml") { env[it] }

    private val fullEnv = mapOf("MY_VAR" to "hello", "REQUIRED_VAR" to "req")

    @Test
    fun `uses env value when present`() {
        assertEquals("hello", load(fullEnv).getString("present"))
    }

    @Test
    fun `falls back to default when env absent`() {
        assertEquals("fallback", load(fullEnv).getString("usesDefault"))
    }

    @Test
    fun `empty default yields empty string, and getStringOrNull returns null`() {
        val c = load(fullEnv)
        assertEquals("", c.getString("emptyDefault"))
        assertNull(c.getStringOrNull("emptyDefault"))
    }

    @Test
    fun `interpolates a placeholder embedded in a larger string`() {
        assertEquals("prefix-hello-suffix", load(fullEnv).getString("embedded"))
    }

    @Test
    fun `leaves plain values and non-strings untouched`() {
        val c = load(fullEnv)
        assertEquals("literal-value", c.getString("plain"))
        assertEquals(8080, c.getInt("number"))
    }

    @Test
    fun `throws when a required placeholder has no default and env is missing`() {
        assertThrows<ConfigException> { load(mapOf("MY_VAR" to "x")) }
    }
}
