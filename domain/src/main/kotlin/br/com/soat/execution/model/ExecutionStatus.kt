package br.com.soat.execution.model

enum class ExecutionStatus {
    AWAITING_DIAGNOSIS,
    RESERVED,
    ENQUEUED,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    CANCELED,
}
