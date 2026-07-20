package br.com.soat.shared.exception

/** Mapeada para HTTP 404 no ErrorHandlingConfiguration. */
open class NotFoundException(message: String) : Throwable(message)

/** Mapeada para HTTP 409 no ErrorHandlingConfiguration (ex.: transição de estado inválida). */
open class ConflictException(message: String) : Throwable(message)
