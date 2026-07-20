package br.com.soat.supply

import br.com.soat.supply.exception.SupplyNotFoundException
import br.com.soat.supply.model.Supply
import br.com.soat.supply.model.request.CreateSupplyRequest
import br.com.soat.supply.repository.SupplyRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class SupplyUseCaseTest {
    private val repo = mockk<SupplyRepository>(relaxed = true)
    private val useCase = SupplyUseCase(repo)

    @Test
    fun `findById throws when not found`() {
        val id = UUID.randomUUID()
        every { repo.findById(id) } returns null
        assertThrows(SupplyNotFoundException::class.java) { useCase.findById(id) }
    }

    @Test
    fun `create maps request to supply`() {
        val slot = slot<Supply>()
        every { repo.create(capture(slot)) } answers { slot.captured }
        val request = CreateSupplyRequest("Filtro de óleo", "desc", 10, "30.00".toBigDecimal())

        useCase.create(request)

        verify { repo.create(any()) }
        assertEquals("Filtro de óleo", slot.captured.name)
        assertEquals(10, slot.captured.quantityInStock)
        assertEquals("30.00".toBigDecimal(), slot.captured.price)
    }

    @Test
    fun `update throws when supply missing`() {
        val id = UUID.randomUUID()
        every { repo.findById(id) } returns null
        assertThrows(SupplyNotFoundException::class.java) {
            useCase.update(id, CreateSupplyRequest("x", null, 1, "1.00".toBigDecimal()))
        }
    }

    @Test
    fun `update copies existing and persists`() {
        val id = UUID.randomUUID()
        val existing = Supply(id = id, name = "old", description = null, quantityInStock = 1, price = "1.00".toBigDecimal())
        every { repo.findById(id) } returns existing
        val slot = slot<Supply>()
        every { repo.update(capture(slot)) } answers { slot.captured }

        useCase.update(id, CreateSupplyRequest("new", "d", 5, "9.90".toBigDecimal()))

        assertEquals(id, slot.captured.id)
        assertEquals("new", slot.captured.name)
        assertEquals(5, slot.captured.quantityInStock)
    }
}
