package br.com.soat.event.model

object SagaEventType {
    const val ORDER_CREATED = "OrderCreated"
    const val SUPPLIES_RESERVED = "SuppliesReserved"
    const val PARTS_UNAVAILABLE = "PartsUnavailable"
    const val PAYMENT_CONFIRMED = "PaymentConfirmed"
    const val EXECUTION_STARTED = "ExecutionStarted"
    const val DIAGNOSE_FINISHED = "DiagnoseFinished"
    const val EXECUTION_FINISHED = "ExecutionFinished"
    const val EXECUTION_FAILED = "ExecutionFailed"
    const val QUOTE_REJECTED = "QuoteRejected"
    const val PAYMENT_FAILED = "PaymentFailed"
    const val RESERVATION_EXPIRED = "ReservationExpired"
}
