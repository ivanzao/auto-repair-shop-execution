package br.com.soat.service

import br.com.soat.service.exception.ServiceNotFoundException
import br.com.soat.service.model.Service
import br.com.soat.service.model.request.CreateServiceRequest
import br.com.soat.service.repository.ServiceRepository
import br.com.soat.shared.model.SupplyRequirement
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ServiceUseCaseTest {
    private val repo = mockk<ServiceRepository>(relaxed = true)
    private val useCase = ServiceUseCase(repo)

    @Test
    fun `findById throws when not found`() {
        val id = UUID.randomUUID()
        every { repo.findById(id) } returns null
        assertThrows(ServiceNotFoundException::class.java) { useCase.findById(id) }
    }

    @Test
    fun `create maps request to service`() {
        val supplyId = UUID.randomUUID()
        val slot = slot<Service>()
        every { repo.create(capture(slot)) } answers { slot.captured }
        val request = CreateServiceRequest(
            name = "Troca de oleo",
            description = "desc",
            price = "100.00".toBigDecimal(),
            requiredSupplies = listOf(SupplyRequirement(supplyId, 1)),
        )

        useCase.create(request)

        verify { repo.create(any()) }
        assertEquals("Troca de oleo", slot.captured.name)
        assertEquals(0, "100.00".toBigDecimal().compareTo(slot.captured.price))
        assertEquals(supplyId, slot.captured.requiredSupplies.single().supplyId)
    }

    @Test
    fun `update throws when service missing`() {
        val id = UUID.randomUUID()
        every { repo.findById(id) } returns null
        assertThrows(ServiceNotFoundException::class.java) {
            useCase.update(id, CreateServiceRequest("x", null, "1.00".toBigDecimal()))
        }
    }

    @Test
    fun `update keeps the id and persists the new values`() {
        val id = UUID.randomUUID()
        val existing = Service(id = id, name = "old", description = null, price = "1.00".toBigDecimal())
        every { repo.findById(id) } returns existing
        val slot = slot<Service>()
        every { repo.update(capture(slot)) } answers { slot.captured }

        useCase.update(id, CreateServiceRequest("new", "d", "9.90".toBigDecimal()))

        assertEquals(id, slot.captured.id)
        assertEquals("new", slot.captured.name)
        assertEquals(0, "9.90".toBigDecimal().compareTo(slot.captured.price))
    }
}
