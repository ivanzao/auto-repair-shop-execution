package br.com.soat.execution

import br.com.soat.execution.dto.ExecutionResponseDTO
import br.com.soat.execution.dto.FailExecutionRequestDTO
import br.com.soat.execution.dto.FinishDiagnosisRequestDTO
import br.com.soat.execution.dto.SuppliesUnavailableResponseDTO
import br.com.soat.execution.model.DiagnosisResult
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.shared.authenticatedUser
import br.com.soat.shared.getUUIDPathParameter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.core.Koin

fun Application.orderRoutes(koin: Koin) {
    val lifecycle = koin.inject<ExecutionLifecycleUseCase>().value
    val reserveSupplies = koin.inject<ReserveSuppliesUseCase>().value

    routing {
        route("/v1") {
            authenticate("mechanic") {
                get("/orders") {
                    val status = call.request.queryParameters["status"]?.let { raw ->
                        try {
                            ExecutionStatus.valueOf(raw)
                        } catch (_: IllegalArgumentException) {
                            throw BadRequestException("Invalid status: $raw")
                        }
                    } ?: ExecutionStatus.AWAITING_DIAGNOSIS
                    call.respond(
                        HttpStatusCode.OK,
                        lifecycle.listByStatus(status).map { ExecutionResponseDTO.from(it) },
                    )
                }

                get("/orders/{orderId}") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(lifecycle.get(orderId)))
                }

                post("/orders/{orderId}/finish-diagnosis") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    val diagnosedBy = call.authenticatedUser()
                    val request = call.receive<FinishDiagnosisRequestDTO>()

                    when (val result = reserveSupplies.finishDiagnosis(orderId, diagnosedBy, request.toModel())) {
                        is DiagnosisResult.Reserved ->
                            call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(result.execution))

                        is DiagnosisResult.Unavailable ->
                            call.respond(
                                HttpStatusCode.Conflict,
                                SuppliesUnavailableResponseDTO.from(result.missing),
                            )
                    }
                }

                post("/orders/{orderId}/start") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(lifecycle.start(orderId)))
                }

                post("/orders/{orderId}/finish") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(lifecycle.finish(orderId)))
                }

                post("/orders/{orderId}/fail") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    val request = call.receive<FailExecutionRequestDTO>()
                    call.respond(
                        HttpStatusCode.OK,
                        ExecutionResponseDTO.from(lifecycle.fail(orderId, request.reason)),
                    )
                }
            }
        }
    }
}
