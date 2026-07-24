package br.com.soat.execution

import br.com.soat.execution.dto.ExecutionResponseDTO
import br.com.soat.execution.dto.FailExecutionRequestDTO
import br.com.soat.execution.model.ExecutionStatus
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

fun Application.executionRoutes(koin: Koin) {
    val useCase = koin.inject<ExecutionLifecycleUseCase>().value

    routing {
        route("/v1") {
            authenticate("mechanic") {
                get("/executions") {
                    val status = call.request.queryParameters["status"]?.let { raw ->
                        try {
                            ExecutionStatus.valueOf(raw)
                        } catch (_: IllegalArgumentException) {
                            throw BadRequestException("Invalid status: $raw")
                        }
                    } ?: ExecutionStatus.QUEUED
                    call.respond(HttpStatusCode.OK, useCase.listByStatus(status).map { ExecutionResponseDTO.from(it) })
                }

                get("/executions/{orderId}") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(useCase.get(orderId)))
                }

                post("/executions/{orderId}/finish-diagnosis") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(useCase.finishDiagnosis(orderId)))
                }

                post("/executions/{orderId}/finish") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(useCase.finish(orderId)))
                }

                post("/executions/{orderId}/fail") {
                    val orderId = call.getUUIDPathParameter("orderId")
                    val request = call.receive<FailExecutionRequestDTO>()
                    call.respond(HttpStatusCode.OK, ExecutionResponseDTO.from(useCase.fail(orderId, request.reason)))
                }
            }
        }
    }
}
