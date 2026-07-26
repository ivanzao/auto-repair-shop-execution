package br.com.soat.execution

import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
import br.com.soat.shared.model.User
import com.fasterxml.jackson.databind.node.MissingNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ExecutionTest {

    private fun execution(status: ExecutionStatus) = Execution(
        orderId = UUID.randomUUID(),
        status = status,
        orderSnapshot = MissingNode.getInstance(),
    )

    private val mechanic = User(
        id = UUID.fromString("00000000-0000-0000-0000-000000000003"),
        document = "12345678909",
    )

    @Test
    fun `happy path AWAITING_DIAGNOSIS to COMPLETED`() {
        val reservationId = UUID.randomUUID()

        val reserved = execution(ExecutionStatus.AWAITING_DIAGNOSIS).reserve(reservationId, mechanic)
        assertEquals(ExecutionStatus.RESERVED, reserved.status)
        assertEquals(reservationId, reserved.reservationId)
        assertEquals(mechanic, reserved.diagnosedBy)

        val enqueued = reserved.enqueue()
        assertEquals(ExecutionStatus.ENQUEUED, enqueued.status)
        val inProgress = enqueued.start()
        assertEquals(ExecutionStatus.IN_PROGRESS, inProgress.status)
        val completed = inProgress.finish()
        assertEquals(ExecutionStatus.COMPLETED, completed.status)
    }

    @Test
    fun `finish and fail only from IN_PROGRESS`() {
        assertEquals(ExecutionStatus.COMPLETED, execution(ExecutionStatus.IN_PROGRESS).finish().status)
        assertEquals(ExecutionStatus.FAILED, execution(ExecutionStatus.IN_PROGRESS).fail().status)
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.ENQUEUED).finish() }
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.RESERVED).fail() }
    }

    @Test
    fun `cancel from AWAITING_DIAGNOSIS RESERVED and ENQUEUED`() {
        assertEquals(ExecutionStatus.CANCELED, execution(ExecutionStatus.AWAITING_DIAGNOSIS).cancel().status)
        assertEquals(ExecutionStatus.CANCELED, execution(ExecutionStatus.RESERVED).cancel().status)
        assertEquals(ExecutionStatus.CANCELED, execution(ExecutionStatus.ENQUEUED).cancel().status)
    }

    @Test
    fun `invalid transitions throw`() {
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.RESERVED).start() }
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.COMPLETED).fail() }
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.IN_PROGRESS).cancel() }
        assertThrows(InvalidExecutionTransitionException::class.java) {
            execution(ExecutionStatus.RESERVED).reserve(UUID.randomUUID(), mechanic)
        }
        assertThrows(InvalidExecutionTransitionException::class.java) {
            execution(ExecutionStatus.AWAITING_DIAGNOSIS).enqueue()
        }
    }

    @Test
    fun `the status machine matches the frozen contract`() {
        assertEquals(
            listOf("AWAITING_DIAGNOSIS", "RESERVED", "ENQUEUED", "IN_PROGRESS", "COMPLETED", "FAILED", "CANCELED"),
            ExecutionStatus.entries.map { it.name },
        )
    }
}
