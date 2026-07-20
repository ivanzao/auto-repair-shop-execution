package br.com.soat.metric

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.ConcurrentHashMap

class MicrometerMetricsPort(private val registry: MeterRegistry) : MetricsPort {

    private val inboundEvents: Counter = Counter.builder("execution_inbound_events_total")
        .description("Total de eventos de saga consumidos e aplicados")
        .register(registry)

    private val suppliesReserved: Counter = Counter.builder("execution_supplies_reserved_total")
        .description("Total de reservas de insumos concluídas com sucesso")
        .register(registry)

    private val suppliesUnavailable: Counter = Counter.builder("execution_supplies_unavailable_total")
        .description("Total de reservas rejeitadas por estoque insuficiente")
        .register(registry)

    private val reservationExpired: Counter = Counter.builder("execution_reservations_expired_total")
        .description("Total de reservas expiradas pelo job de expiração")
        .register(registry)

    private val byStatus: MutableMap<String, Counter> = ConcurrentHashMap()

    override fun inboundEventApplied() = inboundEvents.increment()

    override fun suppliesReserved() = suppliesReserved.increment()

    override fun suppliesUnavailable() = suppliesUnavailable.increment()

    override fun reservationExpired() = reservationExpired.increment()

    override fun executionStatusChanged(status: String) {
        byStatus.computeIfAbsent(status) {
            Counter.builder("execution_by_status_total")
                .description("Total de transições de execução para cada status")
                .tag("status", it)
                .register(registry)
        }.increment()
    }
}
