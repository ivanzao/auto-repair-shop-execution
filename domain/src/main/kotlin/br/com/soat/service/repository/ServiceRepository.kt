package br.com.soat.service.repository

import br.com.soat.service.model.Service
import java.util.UUID

interface ServiceRepository {
    fun findById(id: UUID): Service?
    fun findAll(): List<Service>
    fun findAllByIds(servicesIds: List<UUID>): List<Service>
    fun create(service: Service): Service
    fun update(service: Service): Service
    fun delete(id: UUID)
}
