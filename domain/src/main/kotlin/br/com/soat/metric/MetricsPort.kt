package br.com.soat.metric

interface MetricsPort {
    fun inboundEventApplied()
    fun suppliesReserved()
    fun suppliesUnavailable()
    fun executionStatusChanged(status: String)
    fun reservationExpired()
}
