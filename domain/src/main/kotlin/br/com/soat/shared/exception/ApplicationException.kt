package br.com.soat.shared.exception

abstract class ApplicationException(val errorCode: String, val errorMessage: String? = null): Throwable(errorMessage)