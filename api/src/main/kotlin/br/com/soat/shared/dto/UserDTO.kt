package br.com.soat.shared.dto

import br.com.soat.shared.model.User
import java.util.UUID

data class UserDTO(
    val id: UUID,
    val document: String,
) {
    companion object {
        fun from(user: User) = UserDTO(id = user.id, document = user.document)
    }
}
