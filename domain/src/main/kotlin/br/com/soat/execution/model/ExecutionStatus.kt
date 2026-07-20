package br.com.soat.execution.model

enum class ExecutionStatus {
    RESERVED,
    QUEUED,
    IN_PROGRESS,
    DIAGNOSED,
    COMPLETED,
    FAILED,
    CANCELED,
}
