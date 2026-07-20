package br.com.soat.supply

import br.com.soat.supply.exception.SupplyNotFoundException
import br.com.soat.supply.model.Supply
import br.com.soat.supply.model.request.CreateSupplyRequest
import br.com.soat.supply.repository.SupplyRepository
import java.util.UUID

class SupplyUseCase(
    private val repository: SupplyRepository,
) {

    fun findById(id: UUID) = repository.findById(id) ?: throw SupplyNotFoundException(id)

    fun findAll() = repository.findAll()

    fun create(request: CreateSupplyRequest) =
        repository.create(
            Supply(
                name = request.name,
                description = request.description,
                quantityInStock = request.quantityInStock,
                price = request.price,
            ),
        )

    fun update(id: UUID, request: CreateSupplyRequest): Supply {
        val existing = repository.findById(id) ?: throw SupplyNotFoundException(id)
        return repository.update(
            existing.copy(
                name = request.name,
                description = request.description,
                quantityInStock = request.quantityInStock,
                price = request.price,
            ),
        )
    }

    fun delete(id: UUID) = repository.delete(id)
}
