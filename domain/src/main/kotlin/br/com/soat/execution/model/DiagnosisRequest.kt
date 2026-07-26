package br.com.soat.execution.model

import br.com.soat.shared.model.SupplyRequirement
import java.util.UUID

data class DiagnosisRequest(
    val services: List<UUID>,
    val supplies: List<SupplyRequirement>,
)
