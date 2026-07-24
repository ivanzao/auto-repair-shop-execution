package br.com.soat.event.model

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EventEnvelopeSerializationTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `traceparent is not serialized into the wire body`() {
        val envelope = EventEnvelope(
            eventType = "SuppliesReserved",
            payload = mapper.readTree("""{"orderId":"x"}"""),
            traceparent = "00-54e531bd9ad1f760ab1f329d547f0b7f-9817e25f6d45e950-01",
        )

        val body = mapper.writeValueAsString(envelope)

        assertFalse(body.contains("traceparent"), "traceparent must travel as an SNS attribute, not in the body")
        assertTrue(body.contains("SuppliesReserved"))
        assertTrue(body.contains("payload"))
    }
}
