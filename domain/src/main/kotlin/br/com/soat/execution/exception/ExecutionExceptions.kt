package br.com.soat.execution.exception

import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.shared.exception.ConflictException
import br.com.soat.shared.exception.NotFoundException
import java.util.UUID

class ExecutionNotFoundException(orderId: UUID) :
    NotFoundException("Execution not found for order: $orderId")

class InvalidExecutionTransitionException(orderId: UUID, from: ExecutionStatus, to: ExecutionStatus) :
    ConflictException("Invalid execution transition $from -> $to for order $orderId")
