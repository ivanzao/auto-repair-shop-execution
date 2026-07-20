package br.com.soat.event.model

/**
 * Strings de `eventType` da saga. Confirmadas contra os consumidores reais de order/billing:
 * - `SuppliesReserved` é consumido pelo billing.
 * - `PartsUnavailable` (NÃO "SuppliesUnavailable"), `ExecutionStarted`, `DiagnoseFinished`,
 *   `ExecutionFinished`, `ExecutionFailed`, `ReservationExpired` são consumidos pelo order.
 * - `OrderCreated`, `PaymentConfirmed`, `QuoteRejected`, `PaymentFailed` são consumidos por este serviço.
 */
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
