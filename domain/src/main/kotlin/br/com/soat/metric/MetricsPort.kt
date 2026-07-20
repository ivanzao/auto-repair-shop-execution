package br.com.soat.metric

/**
 * Métricas de negócio do serviço execution. Implementada no módulo metric (Micrometer).
 */
interface MetricsPort {
    fun inboundEventApplied()
    fun suppliesReserved()
    fun suppliesUnavailable()
    fun executionStatusChanged(status: String)
    fun reservationExpired()
}
