package br.com.soat.execution.model

sealed interface DiagnosisResult {
    data class Reserved(val execution: Execution) : DiagnosisResult
    data class Unavailable(val missing: List<MissingSupply>) : DiagnosisResult
}
