package br.com.soat.supply.exception

import br.com.soat.shared.exception.ConflictException
import br.com.soat.shared.exception.NotFoundException
import java.util.UUID

class SupplyNotFoundException(id: UUID) : NotFoundException("Supply not found: $id")

class InsufficientStockException(supplyId: UUID) :
    ConflictException("Insufficient stock for supply: $supplyId")
