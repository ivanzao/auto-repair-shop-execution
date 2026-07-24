package br.com.soat.scheduler

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

class ScheduledTaskRunner(private val tasks: List<ScheduledTask>) {
    private val logger = LoggerFactory.getLogger(ScheduledTaskRunner::class.java)
    private lateinit var scheduler: ScheduledExecutorService

    fun start() {
        scheduler = Executors.newScheduledThreadPool(tasks.size.coerceAtLeast(1))
        tasks.forEach { task ->
            scheduler.scheduleAtFixedRate({
                try {
                    task.execute()
                } catch (e: Exception) {
                    logger.error("Task ${task.getName()} failed", e)
                }
            }, 0, task.getIntervalInSeconds(), TimeUnit.SECONDS)
            logger.info("Scheduled ${task.getName()} every ${task.getIntervalInSeconds()}s")
        }
    }

    fun stop() {
        if (::scheduler.isInitialized) {
            scheduler.shutdown()
            if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) scheduler.shutdownNow()
        }
    }
}
