package br.com.soat.scheduler

interface ScheduledTask {
    fun execute()
    fun getName(): String
    fun getIntervalInSeconds(): Long
}
