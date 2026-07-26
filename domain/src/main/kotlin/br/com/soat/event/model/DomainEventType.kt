package br.com.soat.event.model

object DomainEventType {
    const val ORDER_CREATED = "OrderCreated"
    const val DIAGNOSE_FINISHED = "DiagnoseFinished"
    const val SUPPLIES_UNAVAILABLE = "SuppliesUnavailable"
    const val PAYMENT_CONFIRMED = "PaymentConfirmed"
    const val EXECUTION_STARTED = "ExecutionStarted"
    const val EXECUTION_FINISHED = "ExecutionFinished"
    const val EXECUTION_FAILED = "ExecutionFailed"
    const val QUOTE_REJECTED = "QuoteRejected"
    const val PAYMENT_FAILED = "PaymentFailed"
    const val RESERVATION_EXPIRED = "ReservationExpired"
}
