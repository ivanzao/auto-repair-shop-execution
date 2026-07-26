package br.com.soat.service.exception

import br.com.soat.shared.exception.NotFoundException
import java.util.UUID

class ServiceNotFoundException(id: UUID) : NotFoundException("Service not found: $id")
