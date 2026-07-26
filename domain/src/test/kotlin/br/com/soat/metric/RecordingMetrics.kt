package br.com.soat.metric

class RecordingMetrics : MetricsPort {
    val statuses = mutableListOf<String>()
    var inboundEvents = 0
    var reserved = 0
    var unavailable = 0
    var expired = 0

    override fun inboundEventApplied() { inboundEvents++ }
    override fun suppliesReserved() { reserved++ }
    override fun suppliesUnavailable() { unavailable++ }
    override fun executionStatusChanged(status: String) { statuses += status }
    override fun reservationExpired() { expired++ }
}
