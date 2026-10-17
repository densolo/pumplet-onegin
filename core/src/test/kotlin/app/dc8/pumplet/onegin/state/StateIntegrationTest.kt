package app.dc8.pumplet.onegin.state

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs against the target DB of the docker environment in test-env/ and is skipped when it isn't up.
 * Each test uses its own state table, dropped afterwards.
 */
class StateIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val testEnv: Path = Paths.get(System.getProperty("pumplet.testEnv") ?: "test-env").toAbsolutePath()
    private val stateTable = "pumplet_test_" + System.nanoTime()

    @BeforeEach
    fun requireDocker() {
        assumeTrue(reachable(55433), "docker test DBs not running (see test-env/docker-compose.yml)")
    }

    @AfterEach
    fun dropStateTable() {
        if (!reachable(55433)) return
        Db.connect(config().target, Role.TARGET).use { c -> c.createStatement().use { it.execute("drop table if exists public.$stateTable") } }
    }

    private fun reachable(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("localhost", port), 500); true }
    } catch (e: Exception) {
        false
    }

    /** The test-env job with this test's state table and, optionally, a modified line. */
    private fun config(edit: (String) -> String = { it }): JobConfig {
        val job = dir.resolve("job.yaml")
        val text = Files.readString(testEnv.resolve("job.yaml"))
            .replace("secrets: local.secrets.yaml", "secrets: ${testEnv.resolve("local.secrets.yaml")}") +
            "\nstate:\n  table: public.$stateTable\n"
        Files.writeString(job, edit(text))
        return ConfigLoader(cwd = dir).load(job).config
    }

    @Test
    fun `status without a state table reports nothing and creates nothing`() {
        val cfg = config()
        val report = StatusReader(cfg).read()
        assertFalse(report.tableExists)
        assertNull(report.detail)
        assertFalse(Db.connect(cfg.target, Role.TARGET).use { StateStore(cfg.state.table, cfg.redactor).exists(it) })
    }

    @Test
    fun `a run records its stages and outcome`() {
        val cfg = config()
        SessionManager(cfg).begin("copy").use { s ->
            assertFalse(s.resumed)
            s.stage(Stage.DIFF, summary = { n: Int -> "$n tables to copy" }) { 6 }
            s.put(Kind.TABLE, "app.orders", TableProgress("app.orders", TableStatus.DONE, estRows = 600_000, rows = 600_000, bytes = 42))
            s.succeed()
        }
        val d = assertNotNull(StatusReader(cfg).read().detail)
        assertEquals(SessionStatus.OPEN, d.session.status)
        assertEquals("diff", d.session.stage)
        assertEquals(listOf(RunStatus.SUCCEEDED), d.runs.map { it.status })
        assertEquals("copy", d.runs.single().command)
        assertNotNull(d.runs.single().endedAt)
        assertEquals(StageStatus.DONE, d.stages.single().status)
        assertEquals("6 tables to copy", d.stages.single().summary)
        assertEquals(600_000, d.tables.single().rows)
    }

    @Test
    fun `a failing stage is recorded and rethrown`() {
        val cfg = config()
        SessionManager(cfg).begin("copy").use { s ->
            assertFailsWith<IllegalStateException> { s.stage(Stage.COPY) { error("disk full") } }
            s.fail("1 table failed")
        }
        val d = assertNotNull(StatusReader(cfg).read().detail)
        assertEquals(StageStatus.FAILED, d.stages.single().status)
        assertEquals("disk full", d.stages.single().error)
        assertEquals(RunStatus.FAILED, d.runs.single().status)
        assertEquals("1 table failed", d.runs.single().error)
    }

    @Test
    fun `later commands join the open session until it is completed`() {
        val cfg = config()
        val first = SessionManager(cfg).begin("disable").use { it.succeed(); it.id }
        val second = SessionManager(cfg).begin("copy").use { s ->
            assertTrue(s.resumed)
            assertEquals(2, s.run.seq)
            s.succeed()
            s.completeSession()
            s.id
        }
        assertEquals(first, second)
        val third = SessionManager(cfg).begin("copy").use { s -> assertFalse(s.resumed); s.succeed(); s.id }
        assertNotEquals(first, third)

        val report = StatusReader(cfg).read()
        assertEquals(third, report.detail!!.session.id)
        assertEquals(SessionStatus.COMPLETED, report.history.single().session.status)
        assertEquals("copy", report.history.single().lastRun!!.command)
    }

    @Test
    fun `a different job gets its own session and shows only with all`() {
        val a = config()
        SessionManager(a).begin("copy").use { it.succeed() }
        val b = config { it.replace("rowsPerChunk: 200000", "rowsPerChunk: 100000") }
        SessionManager(b).begin("copy").use { it.succeed() }

        assertEquals(0, StatusReader(b).read().history.size)
        val all = StatusReader(b).read(all = true)
        assertFalse(all.history.single().sameJob)
    }

    @Test
    fun `only one process at a time, and status sees it running`() {
        val cfg = config()
        SessionManager(cfg).begin("copy").use { running ->
            val e = assertFailsWith<StateException> { SessionManager(cfg).begin("copy") }
            assertContains(e.message!!, "another pumplet-onegin process")
            val report = StatusReader(cfg).read()
            assertTrue(report.processRunning)
            assertEquals(RunStatus.RUNNING, report.detail!!.runs.single().status)
            running.succeed()
        }
        assertFalse(StatusReader(cfg).read().processRunning)
    }

    @Test
    fun `a run left running by a dead process shows as interrupted, and is marked so by the next run`() {
        val cfg = config()
        val id = SessionManager(cfg).begin("disable").use { it.succeed(); it.id }
        // What a killed process leaves behind: a RUNNING run and no lock holder.
        Db.connect(cfg.target, Role.TARGET).use { c ->
            val dead = RunInfo(2, "copy", "elsewhere", 1, RunStatus.RUNNING, Instant.now())
            StateStore(cfg.state.table, cfg.redactor).put(c, id, Kind.RUN, dead.key, dead)
        }
        val shown = StatusReader(cfg).read().detail!!.runs.last()
        assertEquals(RunStatus.INTERRUPTED, shown.status)

        SessionManager(cfg).begin("copy").use { s ->
            assertEquals(3, s.run.seq)
            s.succeed()
        }
        val stored = Db.connect(cfg.target, Role.TARGET).use { c ->
            StateStore(cfg.state.table, cfg.redactor).get<RunInfo>(c, id, Kind.RUN, RunInfo.runKey(2))
        }
        assertEquals(RunStatus.INTERRUPTED, stored!!.status)
        assertEquals(SessionManager.INTERRUPTED_MESSAGE, stored.error)
    }

    @Test
    fun `closing without an outcome records the run as interrupted`() {
        val cfg = config()
        SessionManager(cfg).begin("copy").close()
        val run = StatusReader(cfg).read().detail!!.runs.single()
        assertEquals(RunStatus.INTERRUPTED, run.status)
    }

    @Test
    fun `secret values never reach the state table`() {
        val cfg = config()
        SessionManager(cfg).begin("copy").use { it.fail("login failed for password app-ro-pw") }
        val raw = Db.connect(cfg.target, Role.TARGET).use { c ->
            c.createStatement().use { st ->
                st.executeQuery("select string_agg(payload::text, ' ') from public.$stateTable").use { rs -> rs.next(); rs.getString(1) }
            }
        }
        assertFalse(raw.contains("app-ro-pw"))
        assertContains(raw, "****")
    }

    @Test
    fun `missing state schema is a clear error`() {
        val cfg = config { it.replace("table: public.$stateTable", "table: nope_schema.state") }
        val e = assertFailsWith<StateException> { SessionManager(cfg).begin("copy") }
        assertContains(e.message!!, "schema 'nope_schema' for the state table does not exist")
    }

    @Test
    fun `by default state goes into the copied schema`() {
        val cfg = config { it.replace("\nstate:\n  table: public.$stateTable\n", "\n") }
        assertEquals("app.pumplet_onegin_state", cfg.state.table.qualified)
        try {
            SessionManager(cfg).begin("copy").use { it.succeed() }
            assertTrue(Db.connect(cfg.target, Role.TARGET).use { StateStore(cfg.state.table, cfg.redactor).exists(it) })
        } finally {
            Db.connect(cfg.target, Role.TARGET).use { c -> c.createStatement().use { it.execute("drop table if exists app.pumplet_onegin_state") } }
        }
    }

    @Test
    fun `session can be picked by id prefix`() {
        val cfg = config()
        val id = SessionManager(cfg).begin("copy").use { it.succeed(); it.id }
        assertEquals(id, StatusReader(cfg).read(sessionId = id.take(8)).detail!!.session.id)
        assertFailsWith<StateException> { StatusReader(cfg).read(sessionId = "zzzz") }
    }
}
