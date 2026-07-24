package br.com.soat.tracing

import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter

object Tracing {
    val tracer: Tracer get() = GlobalOpenTelemetry.getTracer("saga")

    private val propagator = W3CTraceContextPropagator.getInstance()

    private val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value ->
        carrier?.put(key, value)
    }

    private val getter = object : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }

    fun currentTraceparent(): String? {
        if (!Span.current().spanContext.isValid) return null
        val carrier = mutableMapOf<String, String>()
        propagator.inject(Context.current(), carrier, setter)
        return carrier["traceparent"]
    }

    fun extractContext(traceparent: String?): Context {
        if (traceparent.isNullOrBlank()) return Context.root()
        return propagator.extract(Context.root(), mapOf("traceparent" to traceparent), getter)
    }
}
