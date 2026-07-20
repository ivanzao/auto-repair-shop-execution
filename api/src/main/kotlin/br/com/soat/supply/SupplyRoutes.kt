package br.com.soat.supply

import br.com.soat.shared.getUUIDPathParameter
import br.com.soat.supply.dto.CreateSupplyRequestDTO
import br.com.soat.supply.dto.SupplyResponseDTO
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.core.Koin

fun Application.supplyRoutes(koin: Koin) {
    val useCase = koin.inject<SupplyUseCase>().value

    routing {
        route("/v1") {
            authenticate("admin") {
                post("/supplies") {
                    val request = call.receive<CreateSupplyRequestDTO>()
                    val created = useCase.create(request.toModel())
                    call.respond(HttpStatusCode.Created, SupplyResponseDTO.from(created))
                }

                get("/supplies/{id}") {
                    val id = call.getUUIDPathParameter("id")
                    call.respond(HttpStatusCode.OK, SupplyResponseDTO.from(useCase.findById(id)))
                }

                get("/supplies") {
                    call.respond(HttpStatusCode.OK, useCase.findAll().map { SupplyResponseDTO.from(it) })
                }

                put("/supplies/{id}") {
                    val id = call.getUUIDPathParameter("id")
                    val request = call.receive<CreateSupplyRequestDTO>()
                    call.respond(HttpStatusCode.OK, SupplyResponseDTO.from(useCase.update(id, request.toModel())))
                }

                delete("/supplies/{id}") {
                    val id = call.getUUIDPathParameter("id")
                    useCase.delete(id)
                    call.respond(HttpStatusCode.NoContent)
                }
            }
        }
    }
}
