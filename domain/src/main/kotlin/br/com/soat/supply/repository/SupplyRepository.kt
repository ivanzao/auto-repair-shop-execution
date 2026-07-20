package br.com.soat.supply.repository

import br.com.soat.supply.model.Supply
import java.util.UUID

interface SupplyRepository {
    fun findById(id: UUID): Supply?
    fun findAll(): List<Supply>
    fun findAllByIds(ids: List<UUID>): List<Supply>
    fun create(supply: Supply): Supply
    fun update(supply: Supply): Supply
    fun delete(id: UUID)
}
