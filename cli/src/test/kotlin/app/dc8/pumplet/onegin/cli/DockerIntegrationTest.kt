package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.db.Db
import app.dc8.pumplet.onegin.db.Role
import app.dc8.pumplet.onegin.state.ChunkDone
import app.dc8.pumplet.onegin.state.ChunkPlan
import app.dc8.pumplet.onegin.state.Kind
import app.dc8.pumplet.onegin.state.ObjectKind
import app.dc8.pumplet.onegin.state.ObjectStatus
import app.dc8.pumplet.onegin.state.SavedObject
import app.dc8.pumplet.onegin.state.SessionManager
import app.dc8.pumplet.onegin.state.Stage
import app.dc8.pumplet.onegin.state.TableProgress
import app.dc8.pumplet.onegin.state.TableStatus
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Runs against the docker environment in test-env/ and is skipped when it isn't up:
 *   docker compose -f test-env/docker-compose.yml up -d --wait
 */
class DockerIntegrationTest {
    @TempDir
    lateinit var dir: Path

    private val testEnv: Path = Paths.get(System.getProperty("pumplet.testEnv") ?: "test-env").toAbsolutePath()

    @BeforeEach
    fun requireDocker() {
        assumeTrue(reachable(55432) && reachable(55433), "docker test DBs not running (see test-env/docker-compose.yml)")
    }

    private fun reachable(port: Int) = try {
        Socket().use { it.connect(InetSocketAddress("localhost", port), 500); true }
    } catch (e: Exception) {
        false
    }

    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf(*args), Io(PrintStream(out, true), PrintStream(err, true)))
        return Triple(code, out.toString(), err.toString())
    }

    private fun secrets(text: String): Path {
        val p = dir.resolve("s.yaml")
        Files.writeString(p, text)
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rw-------"))
        return p
    }

    @Test
    fun `check reports selected tables, chunking and exclusions`() {
        val (code, out, err) = run("check", "--config", testEnv.resolve("job.yaml").toString())
        assertEquals(0, code, out + err)
        assertContains(out, "tables: 6 selected of 7 in schemas app")
        assertContains(out, "app.orders       ~600,000 rows  -> 3 chunks")
        assertContains(out, "app.order_items  ~1,200,000 rows  -> 6 chunks")
        assertContains(out, "app.OrderNotes")
        assertContains(out, "excluded: app.audit_log")
        assertFalse(out.contains("other.ignored"))
        assertContains(out, "check OK")
    }

    @Test
    fun `wrong password fails without printing it`() {
        val s = secrets("src: \"wrong-src-password\"\ndst: \"wrong-dst-password\"\n")
        val (code, out, err) = run("check", "--config", testEnv.resolve("job.yaml").toString(), "--secrets", s.toString())
        assertEquals(1, code)
        assertContains(out, "cannot connect or query source")
        assertContains(out, "cannot connect or query target")
        assertFalse((out + err).contains("wrong-src-password"))
        assertFalse((out + err).contains("wrong-dst-password"))
    }

    @Test
    fun `missing privilege and missing schema are reported`() {
        val job = dir.resolve("job.yaml")
        Files.writeString(
            job,
            Files.readString(testEnv.resolve("job.yaml"))
                .replace("secrets: local.secrets.yaml", "secrets: ${testEnv.resolve("local.secrets.yaml")}")
                .replace("schemas: [app]", "schemas: [app, other, nope]")
        )
        val (code, out, _) = run("check", "--config", job.toString())
        assertEquals(1, code)
        assertContains(out, "source schema 'nope' does not exist")
        assertContains(out, "no SELECT privilege on other.ignored")
    }

    private fun stateJob(table: String): Path {
        val job = dir.resolve("state-job.yaml")
        Files.writeString(
            job,
            Files.readString(testEnv.resolve("job.yaml"))
                .replace("secrets: local.secrets.yaml", "secrets: ${testEnv.resolve("local.secrets.yaml")}") +
                "\nstate:\n  table: public.$table\n"
        )
        return job
    }

    @Test
    fun `status shows runs, stages, tables, saved objects and earlier sessions`() {
        val table = "pumplet_cli_test_" + System.nanoTime()
        val job = stateJob(table)
        val config = ConfigLoader().load(job).config
        try {
            val (code0, out0, _) = run("status", "--config", job.toString())
            assertEquals(0, code0)
            assertContains(out0, "no sessions recorded yet")

            SessionManager(config).begin("run").use { s ->
                s.stage(Stage.DIFF) { }
                s.succeed()
                s.completeSession()
            }
            val id = SessionManager(config).begin("disable").use { s ->
                s.stage(Stage.DISABLE, summary = { _: Unit -> "1 foreign key dropped" }) {
                    s.put(Kind.OBJECT, "fk", SavedObject(ObjectKind.FOREIGN_KEY, "app.orders", "orders_customer_id_fkey", "FOREIGN KEY ...", ObjectStatus.DROPPED))
                }
                s.succeed()
                s.id
            }
            SessionManager(config).begin("copy").use { s ->
                // What a copy leaves when its last chunk fails: committed chunks, the plan, a FAILED table.
                s.put(Kind.CHUNK_PLAN, "app.orders", ChunkPlan("app.orders", listOf("id"), listOf(listOf("200000"), listOf("400000"))))
                for (i in 0..1) s.put(Kind.CHUNK, ChunkDone.chunkKey("app.orders", i), ChunkDone("app.orders", i, 200_000, 1000, 10, 2))
                s.put(Kind.TABLE, "app.orders", TableProgress("app.orders", TableStatus.FAILED, estRows = 600_000, error = "connection reset"))
                s.fail("1 table failed")
            }

            val (code, out, err) = run("status", "--config", job.toString())
            assertEquals(0, code, out + err)
            assertContains(out, "session $id  OPEN")
            assertContains(out, "stage:     disable")
            Regex("#1 +disable .* SUCCEEDED").find(out) ?: error("run #1 missing:\n$out")
            Regex("#2 +copy .* FAILED: 1 table failed").find(out) ?: error("run #2 missing:\n$out")
            assertContains(out, "1 foreign key dropped")
            assertContains(out, "app.orders  FAILED  400,000 rows of ~600,000  chunks 2/3: connection reset")
            assertContains(out, "saved objects: 1 foreign key: 1 dropped, 0 pending, 0 restored")
            assertContains(out, "run `enable`")
            assertContains(out, "earlier sessions of this job:")
            Regex("COMPLETED +created .* last run #1 run .* SUCCEEDED").find(out) ?: error("history missing:\n$out")

            val (_, byId, _) = run("status", "--config", job.toString(), "--session", id.take(8), "--limit", "0")
            assertContains(byId, "session $id")
            assertFalse(byId.contains("earlier sessions"))

            val (badCode, _, badErr) = run("status", "--config", job.toString(), "--session", "nope")
            assertEquals(1, badCode)
            assertContains(badErr, "error: no session 'nope'")
        } finally {
            Db.connect(config.target, Role.TARGET).use { c -> c.createStatement().use { it.execute("drop table if exists public.$table") } }
        }
    }

    @Test
    fun `diff of the test environment is clean and writes the json report`() {
        val job = dir.resolve("job.yaml")
        Files.writeString(
            job,
            Files.readString(testEnv.resolve("job.yaml"))
                .replace("secrets: local.secrets.yaml", "secrets: ${testEnv.resolve("local.secrets.yaml")}")
        )
        val (code, out, err) = run("diff", "--config", job.toString())
        assertEquals(0, code, out + err)
        assertContains(out, "tables: 6 selected (6 copy)")
        assertContains(out, "app.order_items  COPY    4 columns, ~1,200,000 rows, 6 chunks")
        assertFalse(out.contains("findings:"))
        assertContains(out, "report written to ${dir.resolve("job.diff.json")}")
        assertContains(out, "diff OK")
        assertTrue(Files.exists(dir.resolve("job.diff.json")))
    }

    @Test
    fun `disable then enable on the test environment, with a pending foreign key in between`() {
        val table = "pumplet_cli_test_" + System.nanoTime()
        val job = stateJob(table)
        val config = ConfigLoader().load(job).config
        fun dst(sql: String) = java.sql.DriverManager.getConnection("jdbc:postgresql://localhost:55433/app", "postgres", "dst-pw")
            .use { c -> c.createStatement().use { it.execute(sql) } }
        try {
            val (code, out, err) = run("disable", "--config", job.toString())
            assertEquals(0, code, out + err)
            assertContains(out, "new session ")
            assertContains(out, "diff: 6 table(s) to copy")
            assertContains(out, "dropped  foreign key app.orders.orders_customer_id_fkey")
            assertContains(out, "disabled trigger     app.orders.orders_audit")
            assertContains(out, "disable: 4 foreign keys, 1 trigger dropped/disabled")

            dst("insert into app.orders values (1, 12345, 'new', 1, now())")
            val (code2, out2, err2) = run("enable", "--config", job.toString())
            assertEquals(1, code2, out2 + err2)
            assertContains(out2, "PENDING  foreign key app.orders.orders_customer_id_fkey: 1 row(s) in app.orders have no matching row")
            assertContains(err2, "enable --skip-pending")

            val (_, status, _) = run("status", "--config", job.toString())
            assertContains(status, "1 pending, 4 restored")
            Regex("#2 +enable .* FAILED: 1 object\\(s\\) pending").find(status) ?: error("run #2 missing:\n$status")

            dst("delete from app.orders")
            val (code3, out3, err3) = run("enable", "--config", job.toString())
            assertEquals(0, code3, out3 + err3)
            assertContains(out3, "restored foreign key app.orders.orders_customer_id_fkey")
            assertContains(err3, "the copy hasn't finished in this session")
        } finally {
            dst("delete from app.orders")
            // Leave the shared target as it was even if a step above failed.
            run("recover", "--config", job.toString())
            Db.connect(config.target, Role.TARGET).use { c -> c.createStatement().use { it.execute("drop table if exists public.$table") } }
        }
    }

    @Test
    fun `run copies the test environment and status shows the finished session`() {
        val table = "pumplet_cli_test_" + System.nanoTime()
        val job = stateJob(table)
        val config = ConfigLoader().load(job).config
        val tables = "app.\"OrderNotes\", app.customers, app.order_items, app.orders, app.products, app.type_zoo"
        fun dst(sql: String) = java.sql.DriverManager.getConnection("jdbc:postgresql://localhost:55433/app", "postgres", "dst-pw")
            .use { c -> c.createStatement().use { it.execute(sql) } }
        try {
            val (code, out, err) = run("run", "--config", job.toString())
            assertEquals(0, code, out + err)
            assertContains(out, "copy: 6 of 6 table(s) done, 1,811,503 rows")
            assertContains(out, "  done    app.order_items: 1,200,000 rows")
            Regex("total +1,811,503 rows").find(out) ?: error("report total missing:\n$out")
            assertContains(out, "enable: 4 foreign keys, 1 trigger restored")
            assertContains(out, "completed")

            val (_, status, _) = run("status", "--config", job.toString())
            assertContains(status, "COMPLETED")
            assertContains(status, "app.order_items  DONE    1,200,000 rows of ~1,200,000  chunks 6/6")
            assertContains(status, "report (run #1): 1,811,503 rows")

            val (fcode, fout, _) = run("fetch-test", "--config", job.toString())
            assertEquals(0, fcode)
            Regex("total +1,811,503 rows").find(fout) ?: error("fetch-test total missing:\n$fout")
        } finally {
            dst("truncate $tables")
            Db.connect(config.target, Role.TARGET).use { c -> c.createStatement().use { it.execute("drop table if exists public.$table") } }
        }
    }
}
