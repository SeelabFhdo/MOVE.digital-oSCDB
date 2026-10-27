package de.movedigital.oscdb.inquiry.internal

import de.movedigital.oscdb.FixedClockConfiguration
import de.movedigital.oscdb.MiniFeedLoader
import de.movedigital.oscdb.TestcontainersConfiguration
import de.movedigital.oscdb.inquiry.InquiryStatus
import de.movedigital.oscdb.inquiry.OutputFormat
import de.movedigital.oscdb.inquiry.ScriptedTranslator
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class, MiniFeedLoader::class, ScriptedTranslator.Config::class)
class InquiryProcessorIT {
    @Autowired lateinit var mini: MiniFeedLoader
    @Autowired lateinit var model: ScriptedTranslator
    @Autowired lateinit var repo: InquiryRepository
    @Autowired lateinit var worker: InquiryWorker
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var clock: Clock

    private var feedId = 0

    @BeforeEach
    fun setUp() {
        mini.reset()
        jdbc.sql("delete from inquiry").update()
        model.reset()
        feedId = mini.load()
    }

    private fun run(question: String = "q", format: OutputFormat = OutputFormat.CSV): StoredResult {
        val job = repo.create(question, format)
        assertTrue(worker.runNext())
        return repo.result(job.id)!!
    }

    @Test
    fun `success stores the rendered csv and meta`() {
        model.answer("select line from v_route where feed_id = $feedId order by line", feedId, assumptions = listOf("current feed"))
        val r = run()
        assertEquals(InquiryStatus.DONE, r.status)
        assertEquals(listOf("line", "440", "U41"), r.body!!.lines().filter { it.isNotEmpty() })
        val meta = r.meta!!
        assertEquals(2, meta.rowCount)
        assertEquals(false, meta.truncated)
        assertEquals(1, meta.attempts)
        assertEquals(listOf(feedId), meta.feeds)
        assertEquals(listOf("current feed"), meta.assumptions)
        assertEquals("scripted", meta.model)
        assertNull(meta.previousSql)
    }

    @Test
    fun `feed ids in the result are added to meta feeds`() {
        val second = mini.load(sha = "sha-2")
        model.answer("select feed_id, count(*) as n from v_stop group by feed_id")
        assertEquals(listOf(feedId, second), run().meta!!.feeds)
    }

    @Test
    fun `an sql error is repaired once`() {
        model.answer("select nope from v_stop")
        model.answer("select name from v_stop where feed_id = $feedId", feedId)
        val r = run()
        assertEquals(InquiryStatus.DONE, r.status)
        assertEquals(2, r.meta!!.attempts)
        assertEquals("select nope from v_stop", r.meta!!.previousSql)
        assertContains(model.calls[1].retry!!.error, "nope")
        assertEquals("{\"sql\":\"select nope from v_stop\"}", model.calls[1].retry!!.previousAnswer)
    }

    @Test
    fun `two failures end the job as FAILED`() {
        model.answer("select nope from v_stop")
        model.answer("select nope2 from v_stop")
        val r = run()
        assertEquals(InquiryStatus.FAILED, r.status)
        assertTrue(r.error!!.startsWith("Query failed:"), r.error)
        assertEquals(2, r.meta!!.attempts)
        assertEquals("select nope2 from v_stop", r.meta!!.sql)
    }

    @Test
    fun `base tables stay out of reach`() {
        model.answer("select * from stop_time")
        model.answer("select * from public.stop_time")
        val r = run()
        assertEquals(InquiryStatus.FAILED, r.status)
    }

    @Test
    fun `a non-select statement is rejected before it runs`() {
        model.answer("delete from v_stop")
        model.answer("delete from v_stop")
        val r = run()
        assertEquals(InquiryStatus.FAILED, r.status)
        assertContains(r.error!!, "SELECT")
        assertEquals(5, jdbc.sql("select count(*) from stop").query(Int::class.java).single())
    }

    @Test
    fun `an unanswerable question fails with the reason after one call`() {
        model.reply { de.movedigital.oscdb.inquiry.internal.Translation(false, "no realtime data", null, emptyList(), emptyList(), "{}", 1) }
        val r = run()
        assertEquals(InquiryStatus.FAILED, r.status)
        assertEquals("Not answerable: no realtime data", r.error)
        assertEquals(1, model.calls.size)
    }

    @Test
    fun `an unavailable model fails the job`() {
        model.reply { throw ModelUnavailableException("Model unavailable: connection refused") }
        val r = run()
        assertEquals(InquiryStatus.FAILED, r.status)
        assertEquals("Model unavailable: connection refused", r.error)
    }

    @Test
    fun `invalid model output is retried`() {
        model.reply { throw InvalidModelOutputException("garbage", "The answer contained no JSON object") }
        model.answer("select name from v_stop where feed_id = $feedId", feedId)
        val r = run()
        assertEquals(InquiryStatus.DONE, r.status)
        assertEquals("garbage", model.calls[1].retry!!.previousAnswer)
    }

    @Test
    fun `geojson without coordinates is retried`() {
        model.answer("select line from v_route")
        model.answer("select name, lat, lon from v_stop where feed_id = $feedId", feedId)
        val r = run(format = OutputFormat.GEOJSON)
        assertEquals(InquiryStatus.DONE, r.status)
        assertContains(model.calls[1].retry!!.error, "lat and lon")
        assertContains(r.body!!, "FeatureCollection")
    }

    @Test
    fun `an unexpected error never leaves the job running`() {
        model.reply { throw IllegalStateException("bug") }
        val r = run()
        assertEquals(InquiryStatus.FAILED, r.status)
        assertEquals("Internal error", r.error)
    }

    @Test
    fun `jobs are claimed in submission order`() {
        val a = repo.create("a", OutputFormat.CSV)
        val b = repo.create("b", OutputFormat.CSV)
        assertEquals(1, repo.find(a.id)!!.queuePosition)
        assertEquals(2, repo.find(b.id)!!.queuePosition)
        assertEquals(a.id, repo.claimNext()!!.id)
        assertEquals(InquiryStatus.RUNNING, repo.find(a.id)!!.status)
        assertNull(repo.find(a.id)!!.queuePosition)
        assertEquals(b.id, repo.claimNext()!!.id)
        assertNull(repo.claimNext())
        assertEquals(false, worker.runNext())
    }

    @Test
    fun `recover requeues running jobs`() {
        val a = repo.create("a", OutputFormat.CSV)
        repo.claimNext()
        worker.recover()
        val job = repo.find(a.id)!!
        assertEquals(InquiryStatus.QUEUED, job.status)
        assertNull(job.startedAt)
    }

    @Test
    fun `expired jobs disappear`() {
        val old = repo.create("old", OutputFormat.CSV)
        val fresh = repo.create("fresh", OutputFormat.CSV)
        jdbc.sql("update inquiry set created_at = :t where id = :id")
            .param("t", OffsetDateTime.ofInstant(clock.instant().minus(Duration.ofHours(25)), ZoneOffset.UTC)).param("id", old.id).update()
        assertNull(repo.find(old.id))
        assertNull(repo.result(old.id))
        assertNotNull(repo.find(fresh.id))
        worker.cleanup()
        assertEquals(1, jdbc.sql("select count(*) from inquiry").query(Int::class.java).single())
    }
}
