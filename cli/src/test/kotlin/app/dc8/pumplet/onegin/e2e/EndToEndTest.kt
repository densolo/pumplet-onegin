package app.dc8.pumplet.onegin.e2e

import app.dc8.pumplet.onegin.cli.Io
import app.dc8.pumplet.onegin.cli.LogFile
import app.dc8.pumplet.onegin.cli.runCli
import app.dc8.pumplet.onegin.e2e.E2eEnvironment.connect
import app.dc8.pumplet.onegin.e2e.E2eEnvironment.exec
import app.dc8.pumplet.onegin.e2e.E2eEnvironment.scalar
import app.dc8.pumplet.onegin.e2e.E2eEnvironment.target
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The plan's end-to-end scenarios, through the CLI, against two Testcontainers
 * PostgreSQL databases whose schemas Liquibase creates (see e2e/changelog.sql).
 * Skipped when Docker isn't available.
 */
class EndToEndTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var db: String
    private lateinit var job: Path

    @BeforeEach
    fun setUp() {
        assumeTrue(E2eEnvironment.dockerAvailable, "Docker is not available")
        db = E2eEnvironment.freshTarget()
        job = E2eEnvironment.job(dir, db)
    }

    @AfterEach
    fun tearDown() = LogFile.detach()

    private fun cli(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf(*args), Io(PrintStream(out, true), PrintStream(err, true)))
        return Triple(code, out.toString(), err.toString())
    }

    private fun <T> tgt(block: (java.sql.Connection) -> T): T = connect(target, db).use(block)

    private fun assertIdentical() = assertEquals(E2eEnvironment.sourceChecksums(), tgt { E2eEnvironment.checksums(it) })

    /** Foreign keys and their validation, secondary indexes, triggers and their mode, as the target has them. */
    private fun targetShape(): String = tgt {
        scalar(it, """
            select string_agg(x, '; ' order by x) from (
              select conname || ' ' || pg_get_constraintdef(oid) || ' ' || convalidated as x from pg_constraint
              where connamespace = 'shop'::regnamespace and contype = 'f'
              union all select indexrelid::regclass::text from pg_index i join pg_class c on c.oid = i.indrelid
              where c.relnamespace = 'shop'::regnamespace and not i.indisprimary
              union all select tgname || ' ' || tgenabled::text from pg_trigger where not tgisinternal
            ) s
        """.trimIndent())
    }

    @Test
    fun `diff reports the schema differences correctly`() {
        val full = E2eEnvironment.job(dir, db, exclude = "[]")
        val (code, out, err) = cli("diff", "--config", full.toString(), "--verbose")
        assertEquals(1, code, out + err)
        assertContains(out, "tables: 12 selected (10 copy, 1 skip, 1 blocked)")
        assertContains(out, "shop.legacy       SKIP    missing in target")
        assertContains(out, "shop.mismatch     BLOCKED v: type text in source, integer in target")
        assertContains(out, "WARNING  shop.emp.salary: column missing in target")
        assertContains(out, "WARNING  shop.emp.title: type character varying(20) in source, character varying(60) in target: safe widening")
        assertContains(out, "INFO     shop.emp.nickname: only in target")
        assertContains(out, "INFO     shop.event_log: no primary key")
        assertContains(out, "INFO     shop.document: large-object column(s) body")
        assertContains(out, "shop.measurement  COPY    4 columns, ~300,000 rows, 15 chunks")
        assertContains(out, "shop.emp          COPY    4 columns, ~5,000 rows, text format")
        // Generated on both sides: no finding, just not copied; the object table lists it as the same.
        assertFalse(out.substringAfter("findings:").contains("shop.product.total"))
        Regex("""shop\.product\.total +column .*same""").find(out) ?: error("generated column row missing:\n$out")
        assertContains(out, "overall: BLOCKED: 1 blocker(s)")
        assertContains(out, "diff found 1 blocking error(s)")
        assertTrue(Files.exists(dir.resolve("e2e.diff.json")))
    }

    @Test
    fun `a full run produces identical data and restores everything`() {
        val before = targetShape()
        val (code, out, err) = cli("run", "--config", job.toString())
        assertEquals(0, code, out + err)
        assertContains(out, "disable: 2 foreign keys, 1 trigger dropped/disabled")
        assertContains(out, "copy: 10 of 10 table(s) done")
        assertContains(out, "completed")

        assertIdentical()
        assertEquals(before, targetShape(), "constraints, indexes and triggers back exactly as they were")
        // Sequences follow the copied data.
        tgt { c ->
            exec(c, "insert into shop.ticket (subject) values ('new'); insert into shop.note (body) values ('new')")
            assertEquals("301", scalar(c, "select max(id) from shop.ticket"))
            assertEquals("401", scalar(c, "select max(id) from shop.note"))
            assertEquals("new (touched)", scalar(c, "select body from shop.note where id = 401"), "trigger is back on")
        }
        val (_, status, _) = cli("status", "--config", job.toString())
        assertContains(status, "COMPLETED")
        assertContains(status, "shop.measurement  DONE    300,000 rows of ~300,000  chunks 15/15")
        assertTrue(Files.readString(dir.resolve("e2e.log")).contains("stage copy finished"))
    }

    @Test
    fun `the tool refuses to run on a non-empty target`() {
        tgt { exec(it, "insert into shop.visit values (gen_random_uuid(), now())") }
        val before = targetShape()
        val (code, _, err) = cli("run", "--config", job.toString())
        assertEquals(1, code)
        assertContains(err, "these target tables already have rows: shop.visit")
        assertEquals(before, targetShape(), "nothing dropped")
        assertEquals(1, tgt { E2eEnvironment.rows(it) })
    }

    @Test
    fun `killed mid-copy, then resumed, with no duplicates`() {
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val proc = ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), "app.dc8.pumplet.onegin.cli.MainKt", "run", "--config", job.toString())
            .redirectErrorStream(true).redirectOutput(dir.resolve("child.out").toFile()).start()
        // Wait until some chunks are committed, then kill -9.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (System.nanoTime() < deadline) {
            val committed = runCatching { tgt { scalar(it, "select count(*) from shop.pumplet_onegin_state where kind = 'chunk'").toInt() } }.getOrDefault(0)
            if (committed >= 4 || !proc.isAlive) break
            Thread.sleep(20)
        }
        assertTrue(proc.isAlive, "the copy finished before it could be killed:\n" + Files.readString(dir.resolve("child.out")))
        proc.destroyForcibly().waitFor()

        val (committedRows, targetRows) = tgt {
            scalar(it, "select sum((payload->>'rows')::bigint) from shop.pumplet_onegin_state where kind = 'chunk'").toLong() to E2eEnvironment.rows(it)
        }
        assertEquals(committedRows, targetRows, "exactly the committed chunks are in the target")
        assertTrue(targetRows < 400_000, "killed before the end")

        val (code, out, err) = cli("run", "--config", job.toString())
        assertEquals(0, code, out + err)
        assertContains(err, "resuming the copy")
        assertIdentical()
        tgt { c ->
            assertEquals("0", scalar(c, "select count(*) - count(distinct key) from shop.pumplet_onegin_state where kind = 'chunk'"))
        }
        val (_, status, _) = cli("status", "--config", job.toString())
        Regex("#1 +run .* INTERRUPTED").find(status) ?: error("killed run not shown as interrupted:\n$status")
    }

    @Test
    fun `recover after a failed copy puts every constraint back`() {
        val before = targetShape()
        tgt { exec(it, "alter table shop.measurement add constraint small_ids check (id < 250000)") }
        val (code, _, err) = cli("run", "--config", job.toString())
        assertEquals(1, code)
        assertContains(err, "1 table(s) failed: shop.measurement")
        val (_, status, _) = cli("status", "--config", job.toString())
        assertContains(status, "3 dropped")

        val (rcode, rout, rerr) = cli("recover", "--config", job.toString())
        assertEquals(0, rcode, rout + rerr)
        assertContains(rout, "recover: 2 foreign keys, 1 trigger restored")
        tgt { exec(it, "alter table shop.measurement drop constraint small_ids") }
        assertEquals(before, targetShape())
    }

    @Test
    fun `enable with a violating row leaves the foreign key pending until fixed`() {
        assertEquals(0, cli("disable", "--config", job.toString()).first)
        assertEquals(0, cli("copy", "--config", job.toString()).first)
        tgt { exec(it, "insert into shop.emp (id, dept_id, name) values (999999, 424242, 'orphan')") }

        val (code, out, err) = cli("enable", "--config", job.toString())
        assertEquals(1, code)
        assertContains(out, "PENDING  foreign key shop.emp.emp_dept_id_fkey: 1 row(s) in shop.emp have no matching row")
        assertContains(err, "enable --skip-pending")

        tgt { exec(it, "delete from shop.emp where id = 999999") }
        val (code2, out2, err2) = cli("enable", "--config", job.toString())
        assertEquals(0, code2, out2 + err2)
        assertContains(out2, "restored foreign key shop.emp.emp_dept_id_fkey")
        assertContains(out2, "completed")
        assertIdentical()
    }

    @Test
    fun `fetch-test touches nothing on the target`() {
        val before = targetShape()
        val (code, out, err) = cli("fetch-test", "--config", job.toString())
        assertEquals(0, code, out + err)
        assertContains(out, "shop.measurement")
        Regex("""shop\.measurement +300,000 rows""").find(out) ?: error(out)
        assertEquals(0, tgt { E2eEnvironment.rows(it) })
        assertEquals("f", tgt { scalar(it, "select to_regclass('shop.pumplet_onegin_state') is not null") })
        assertEquals(before, targetShape())
    }

    @Test
    fun `include and exclude filters decide what is copied`() {
        val filtered = E2eEnvironment.job(dir, db, extra = "")
            .also { Files.writeString(it, Files.readString(it).replace("  exclude: [shop.mismatch]", "  include: [shop.d*, shop.emp, shop.l*]\n  exclude: [shop.legacy]")) }
        val (code, out, err) = cli("run", "--config", filtered.toString())
        assertEquals(0, code, out + err)
        assertContains(out, "copy: 4 of 4 table(s) done")
        tgt { c ->
            assertEquals("50", scalar(c, "select count(*) from shop.dept"))
            assertEquals("5000", scalar(c, "select count(*) from shop.emp"))
            assertEquals("31", scalar(c, "select count(*) from shop.document"))
            assertEquals("60000", scalar(c, "select count(*) from shop.ledger"))
            assertEquals("0", scalar(c, "select count(*) from shop.measurement"))
            assertEquals("0", scalar(c, "select count(*) from shop.note"))
        }
    }
}
