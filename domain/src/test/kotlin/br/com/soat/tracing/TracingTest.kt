package br.com.soat.tracing

import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TracingTest {
    private val traceId = "00000000000000000000000000000abc"
    private val spanId = "0000000000000def"

    private fun contextWith(tid: String, sid: String): Context {
        val sc = SpanContext.createFromRemoteParent(tid, sid, TraceFlags.getSampled(), TraceState.getDefault())
        return Context.root().with(Span.wrap(sc))
    }

    @Test
    fun `currentTraceparent returns null when no valid span`() {
        val result = Context.root().makeCurrent().use { Tracing.currentTraceparent() }
        assertNull(result)
    }

    @Test
    fun `roundtrip inject then extract preserves ids`() {
        val header = contextWith(traceId, spanId).makeCurrent().use { Tracing.currentTraceparent() }
        assertTrue(header!!.contains(traceId))

        val extracted = Tracing.extractContext(header)
        val sc = Span.fromContext(extracted).spanContext
        assertEquals(traceId, sc.traceId)
        assertEquals(spanId, sc.spanId)
    }

    @Test
    fun `extractContext with null returns invalid root span`() {
        val sc = Span.fromContext(Tracing.extractContext(null)).spanContext
        assertTrue(!sc.isValid)
    }
}
