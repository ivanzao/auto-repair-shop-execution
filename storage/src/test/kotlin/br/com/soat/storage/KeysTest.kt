package br.com.soat.storage

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class KeysTest {
    @Test
    fun `builds prefixed keys`() {
        val id = UUID.fromString("11111111-1111-1111-1111-111111111111")
        assertEquals("SUPPLY#$id", Keys.supply(id))
        assertEquals("ORDER#$id", Keys.order(id))
        assertEquals("RES#$id", Keys.reservation(id))
        assertEquals("OUTBOX#$id", Keys.outbox(id))
        assertEquals("PROC#$id", Keys.processed(id))
        assertEquals("CONS#worker", Keys.consumer("worker"))
        assertEquals("EXEC#QUEUED", Keys.execStatus("QUEUED"))
    }
}
