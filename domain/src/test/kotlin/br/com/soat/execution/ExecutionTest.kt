package br.com.soat.execution

import br.com.soat.execution.exception.InvalidExecutionTransitionException
import br.com.soat.execution.model.Execution
import br.com.soat.execution.model.ExecutionStatus
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

    @Test
    fun `happy path RESERVED to COMPLETED`() {
        val reserved = execution(ExecutionStatus.RESERVED)
        val queued = reserved.queue()
        assertEquals(ExecutionStatus.QUEUED, queued.status)
        val inProgress = queued.start()
        assertEquals(ExecutionStatus.IN_PROGRESS, inProgress.status)
        val diagnosed = inProgress.finishDiagnosis()
        assertEquals(ExecutionStatus.DIAGNOSED, diagnosed.status)
        val completed = diagnosed.finish()
        assertEquals(ExecutionStatus.COMPLETED, completed.status)
    }

    @Test
    fun `fail from IN_PROGRESS and DIAGNOSED`() {
        assertEquals(ExecutionStatus.FAILED, execution(ExecutionStatus.IN_PROGRESS).fail().status)
        assertEquals(ExecutionStatus.FAILED, execution(ExecutionStatus.DIAGNOSED).fail().status)
    }

    @Test
    fun `cancel from RESERVED and QUEUED`() {
        assertEquals(ExecutionStatus.CANCELED, execution(ExecutionStatus.RESERVED).cancel().status)
        assertEquals(ExecutionStatus.CANCELED, execution(ExecutionStatus.QUEUED).cancel().status)
    }

    @Test
    fun `invalid transitions throw`() {
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.RESERVED).finish() }
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.RESERVED).start() }
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.COMPLETED).fail() }
        assertThrows(InvalidExecutionTransitionException::class.java) { execution(ExecutionStatus.IN_PROGRESS).cancel() }
    }
}
