package br.com.soat.config

import br.com.soat.auth.jwtBearer
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication

fun Application.configureAuthentication() {
    install(Authentication) {
        jwtBearer("admin") {
            allowedRoles = setOf("ADMIN")
        }
        jwtBearer("mechanic") {
            allowedRoles = setOf("ADMIN", "MECHANIC")
        }
    }
}
