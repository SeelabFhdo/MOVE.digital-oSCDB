package de.movedigital.oscdb.inquiry.internal

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Processes queued inquiries one at a time (the local model handles one generation at a time).
 * The schedules are active only with `oscdb.inquiry.worker-enabled=true` (see [InquirySchedulingConfig]).
 */
@Component
class InquiryWorker(private val repo: InquiryRepository, private val processor: InquiryProcessor) : SmartInitializingSingleton {
    private val log = LoggerFactory.getLogger(InquiryWorker::class.java)

    /** Processes the oldest queued job; false if there was none. */
    fun runNext(): Boolean {
        val job = repo.claimNext() ?: return false
        log.info("Inquiry {} started ({}): {}", job.id, job.format.wire, job.question)
        processor.process(job)
        log.info("Inquiry {} finished: {}", job.id, repo.find(job.id)?.status)
        return true
    }

    @Scheduled(fixedDelayString = "\${oscdb.inquiry.poll-interval}")
    fun poll() {
        while (runNext()) {
            // drain the queue
        }
    }

    @Scheduled(cron = "0 0 * * * *")
    fun cleanup() {
        val n = repo.deleteExpired()
        if (n > 0) log.info("Deleted {} expired inquiries", n)
    }

    /**
     * Runs after all singletons exist (Flyway has migrated) but before scheduled polling starts, so only jobs
     * interrupted by a previous run are requeued, never one this run has already claimed.
     */
    override fun afterSingletonsInstantiated() = recover()

    fun recover() {
        val n = repo.requeueRunning()
        if (n > 0) log.warn("Requeued {} inquiries interrupted by a restart", n)
    }
}
