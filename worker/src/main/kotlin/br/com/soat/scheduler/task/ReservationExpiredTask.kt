package br.com.soat.scheduler.task

import br.com.soat.execution.ExpireReservationsUseCase
import br.com.soat.scheduler.ScheduledTask

class ReservationExpiredTask(
    private val useCase: ExpireReservationsUseCase,
    private val intervalSeconds: Long = 3600,
) : ScheduledTask {
    override fun execute() = useCase.run()
    override fun getName() = "reservation-expired"
    override fun getIntervalInSeconds() = intervalSeconds
}
