package br.com.soat.service

import br.com.soat.service.exception.ServiceNotFoundException
import br.com.soat.service.model.Service
import br.com.soat.service.model.request.CreateServiceRequest
import br.com.soat.service.repository.ServiceRepository
import java.util.UUID

class ServiceUseCase(
    private val repository: ServiceRepository,
) {

    fun findById(id: UUID) = repository.findById(id) ?: throw ServiceNotFoundException(id)

    fun findAll() = repository.findAll()

    fun create(request: CreateServiceRequest) =
        repository.create(
            Service(
                name = request.name,
                description = request.description,
                price = request.price,
                requiredSupplies = request.requiredSupplies,
            ),
        )

    fun update(id: UUID, request: CreateServiceRequest): Service {
        val existing = repository.findById(id) ?: throw ServiceNotFoundException(id)
        return repository.update(
            existing.copy(
                name = request.name,
                description = request.description,
                price = request.price,
                requiredSupplies = request.requiredSupplies,
            ),
        )
    }

    fun delete(id: UUID) = repository.delete(id)
}
