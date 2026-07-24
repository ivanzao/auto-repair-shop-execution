package br.com.soat.shared.exception

open class NotFoundException(message: String) : Throwable(message)

open class ConflictException(message: String) : Throwable(message)
