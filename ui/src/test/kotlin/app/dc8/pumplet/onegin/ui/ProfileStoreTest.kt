package app.dc8.pumplet.onegin.ui

import app.dc8.pumplet.onegin.config.ConfigLoader
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProfileStoreTest {
    @TempDir
    lateinit var dir: Path

    private val full = Profile(
        name = "uat-local",
        sourceUrl = "jdbc:postgresql://uat:5432/app", sourceUser = "app_ro", sourcePassword = "s3c\"r'et\\pw",
        schemas = listOf("app_owner", "Other"),
        targetUrl = "jdbc:postgresql://localhost:5432/app", targetUser = "postgres", targetPassword = "local-pw",
        include = listOf("app_owner.order*"), exclude = listOf("app_owner.audit_*"),
        readers = 12, writers = 3, rowsPerChunk = 50_000, minRowsToSplit = 100_000, maxMemoryMb = 512,
        dropForeignKeys = true, dropSecondaryIndexes = true, disableUserTriggers = false,
        allowTypeDifferences = true, raiseSequences = false, stateTable = "ops.copy_state",
    )

    @Test
    fun `save and load round-trip every field, and the CLI accepts the job`() {
        val store = ProfileStore(dir)
        val v = store.save(full)
        assertIs<Validation.Ok>(v, v.toString())
        assertEquals(full, store.load("uat-local"))
        assertEquals(listOf("uat-local"), store.list())

        val config = ConfigLoader().load(store.jobFile("uat-local")).config
        assertEquals("s3c\"r'et\\pw", config.source.password!!.reveal())
        assertEquals(listOf("app_owner", "Other"), config.source.schemas)
        assertEquals("ops.copy_state", config.state.table.qualified)
        assertTrue(config.diff.allowTypeDifferences)
        assertFalse(config.sequences.raiseToSource)
    }

    @Test
    fun `passwords live only in secrets yaml, readable by the owner only`() {
        val store = ProfileStore(dir)
        store.save(full)
        assertFalse(Files.readString(store.jobFile("uat-local")).contains("local-pw"))
        assertContains(Files.readString(store.secretsFile("uat-local")), "local-pw")
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.secretsFile("uat-local"))))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(store.dir("uat-local"))))
    }

    @Test
    fun `empty passwords and users are left out`() {
        val store = ProfileStore(dir)
        store.save(full.copy(name = "trust", sourcePassword = "", targetPassword = "", targetUser = ""))
        val job = Files.readString(store.jobFile("trust"))
        assertFalse(job.contains("password:"))
        assertFalse(job.contains("user: \"\""))
        assertEquals("", store.load("trust").targetPassword)
    }

    @Test
    fun `invalid profiles are saved but reported, offline`() {
        val store = ProfileStore(dir)
        val v = store.save(full.copy(name = "broken", schemas = emptyList(), sourceUrl = "mysql://x"))
        assertIs<Validation.Invalid>(v)
        assertTrue(v.problems.any { it.contains("source.url: expected a jdbc:postgresql: URL") }, v.problems.toString())
        assertTrue(v.problems.any { it.contains("source.schemas: list at least one schema") })
    }

    @Test
    fun `rename moves the folder, names are checked, delete removes everything`() {
        val store = ProfileStore(dir)
        store.save(full)
        Files.writeString(store.dir("uat-local").resolve("job.log"), "history")
        store.save(full.copy(name = "uat-2"), previousName = "uat-local")
        assertEquals(listOf("uat-2"), store.list())
        assertTrue(Files.exists(store.dir("uat-2").resolve("job.log")), "the log moves with it")

        assertFailsWith<IllegalArgumentException> { store.save(full.copy(name = "uat-2")) }
        assertFailsWith<IllegalArgumentException> { store.save(full.copy(name = "a/b")) }
        assertEquals("uat-2-2", store.freeName("uat-2"))

        store.delete("uat-2")
        assertEquals(emptyList(), store.list())
        assertFalse(Files.exists(store.dir("uat-2")))
    }

    @Test
    fun `a hand-edited profile with other secret names still loads`() {
        val store = ProfileStore(dir)
        Files.createDirectories(store.dir("manual"))
        Files.writeString(store.jobFile("manual"), """
            secrets: secrets.yaml
            source:
              url: jdbc:postgresql://h/app
              password: ${'$'}{secret:uat}
              schemas: [app]
            target:
              url: jdbc:postgresql://localhost/app
        """.trimIndent())
        Files.writeString(store.secretsFile("manual"), "uat: \"from-shell\"\n")
        val p = store.load("manual")
        assertEquals("from-shell", p.sourcePassword)
        assertEquals(10, p.readers, "defaults for what the file leaves out")
    }

    @Test
    fun `the runner runs a CLI command in-process and streams its lines`() {
        val store = ProfileStore(dir)
        store.save(full)
        val lines = CopyOnWriteArrayList<OutputLine>()
        val done = CountDownLatch(1)
        var exit = -1
        val runner = CommandRunner({ _, l -> lines += l }, { _, code -> exit = code; done.countDown() })
        assertTrue(runner.start("uat-local", listOf("validate", "--config", store.jobFile("uat-local").toString())))
        assertTrue(done.await(30, TimeUnit.SECONDS))
        assertEquals(0, exit)
        assertTrue(lines.any { it.stream == OutputLine.Stream.OUT && it.text.startsWith("configuration OK") }, lines.toString())
        assertTrue(lines.none { it.text.contains("local-pw") })
        assertEquals("exit code 0", lines.last().text)
    }
}
