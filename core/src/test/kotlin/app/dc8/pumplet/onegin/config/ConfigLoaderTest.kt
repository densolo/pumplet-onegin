package app.dc8.pumplet.onegin.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigLoaderTest {
    @TempDir
    lateinit var dir: Path

    private val minimalJob = """
        secrets: s.yaml
        source:
          url: jdbc:postgresql://uat-host:5432/app
          user: app_ro
          password: ${'$'}{secret:uat}
          schemas: [app]
        target:
          url: jdbc:postgresql://localhost:5432/app
          user: postgres
          password: ${'$'}{secret:local}
    """.trimIndent()

    private fun write(name: String, text: String, perms: String = "rw-------"): Path {
        val p = dir.resolve(name)
        Files.createDirectories(p.parent)
        Files.writeString(p, text)
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms))
        return p
    }

    private fun loader() = ConfigLoader(cwd = dir, home = dir.resolve("home"))

    private fun problems(block: () -> Unit): String =
        assertFailsWith<ConfigException> { block() }.problems.joinToString("\n")

    @Test
    fun `loads job with secrets and applies defaults`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: \"uat-secret\"\nlocal: \"local-secret\"\n")
        val (config, warnings) = loader().load(job)

        assertEquals(emptyList(), warnings)
        assertEquals(dir.resolve("s.yaml"), config.secretsFile)
        assertEquals("uat-secret", config.source.password!!.reveal())
        assertEquals("local-secret", config.target.password!!.reveal())
        assertEquals(listOf("app"), config.source.schemas)
        assertEquals(ThreadsConfig(10, 4), config.threads)
        assertEquals(ChunkingConfig(200_000, 500_000), config.chunking)
        assertEquals(BufferConfig(1024), config.buffer)
        assertEquals(ConstraintsConfig(true, false, true), config.constraints)
        assertEquals(TablesConfig(emptyList(), emptyList()), config.tables)
    }

    @Test
    fun `loads the full example from the plan`() {
        val job = write(
            "job.yaml", minimalJob + "\n" + """
            tables:
              include: [app.order*]
              exclude: [app.audit_*]
            threads:
              readers: 12
              writers: 3
            chunking:
              rowsPerChunk: 100000
              minRowsToSplit: 300000
            buffer:
              maxMemoryMb: 512
            constraints:
              dropForeignKeys: false
              dropSecondaryIndexes: true
              disableUserTriggers: false
            """.trimIndent()
        )
        write("s.yaml", "uat: a1b2c3\nlocal: d4e5f6\n")
        val config = loader().load(job).config
        assertEquals(TablesConfig(listOf("app.order*"), listOf("app.audit_*")), config.tables)
        assertEquals(ThreadsConfig(12, 3), config.threads)
        assertEquals(ChunkingConfig(100_000, 300_000), config.chunking)
        assertEquals(BufferConfig(512), config.buffer)
        assertEquals(ConstraintsConfig(false, true, false), config.constraints)
    }

    @Test
    fun `secrets path resolves against the job file directory, not cwd`() {
        val job = write("jobs/job.yaml", minimalJob.replace("secrets: s.yaml", "secrets: ../keys/s.yaml"))
        write("keys/s.yaml", "uat: aaaa\nlocal: bbbb\n")
        val config = ConfigLoader(cwd = dir.resolve("elsewhere")).load(job).config
        assertEquals(dir.resolve("keys/s.yaml"), config.secretsFile)
    }

    @Test
    fun `tilde in secrets path expands to home`() {
        val job = write("job.yaml", minimalJob.replace("secrets: s.yaml", "secrets: ~/.pumplet-onegin/s.yaml"))
        write("home/.pumplet-onegin/s.yaml", "uat: aaaa\nlocal: bbbb\n")
        assertEquals(dir.resolve("home/.pumplet-onegin/s.yaml"), loader().load(job).config.secretsFile)
    }

    @Test
    fun `--secrets overrides the job file and resolves against cwd`() {
        val job = write("job.yaml", minimalJob)
        write("other/override.yaml", "uat: override-uat\nlocal: override-local\n")
        val config = ConfigLoader(cwd = dir.resolve("other")).load(job, secretsOverride = "override.yaml").config
        assertEquals(dir.resolve("other/override.yaml"), config.secretsFile)
        assertEquals("override-uat", config.source.password!!.reveal())
    }

    @Test
    fun `missing secret names the key and never the values`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: topsecret-uat\n")
        val msg = problems { loader().load(job) }
        assertContains(msg, "target.password: secret 'local' not found")
        assertFalse(msg.contains("topsecret-uat"))
    }

    @Test
    fun `plain password literal is rejected`() {
        val job = write("job.yaml", minimalJob.replace("\${secret:uat}", "hunter2-literal"))
        write("s.yaml", "uat: aaaa\nlocal: bbbb\n")
        val msg = problems { loader().load(job) }
        assertContains(msg, "source.password: plain-text passwords are not allowed")
        assertContains(msg, "secrets file")
        assertFalse(msg.contains("hunter2-literal"))
    }

    @Test
    fun `non-string password is rejected without echoing it`() {
        val job = write("job.yaml", minimalJob.replace("\${secret:uat}", "12345678"))
        val msg = problems { loader().load(job) }
        assertContains(msg, "source.password: expected a secret reference")
        assertFalse(msg.contains("12345678"))
    }

    @Test
    fun `password embedded in the JDBC URL is rejected`() {
        val job = write(
            "job.yaml",
            minimalJob.replace("jdbc:postgresql://uat-host:5432/app", "jdbc:postgresql://uat-host:5432/app?password=leaky")
        )
        val msg = problems { loader().load(job) }
        assertContains(msg, "source.url: must not contain a password")
        assertFalse(msg.contains("leaky"))
    }

    @Test
    fun `secret reference without any secrets file is an error`() {
        val job = write("job.yaml", minimalJob.replace("secrets: s.yaml\n", ""))
        assertContains(problems { loader().load(job) }, "no secrets file is configured")
    }

    @Test
    fun `missing secrets file is an error`() {
        val job = write("job.yaml", minimalJob)
        assertContains(problems { loader().load(job) }, "secrets file not found")
    }

    @Test
    fun `no passwords and no secrets file is fine`() {
        val job = write(
            "job.yaml", """
            source:
              url: jdbc:postgresql://a/app
              schemas: [app]
            target:
              url: jdbc:postgresql://b/app
            """.trimIndent()
        )
        val config = loader().load(job).config
        assertNull(config.secretsFile)
        assertNull(config.source.password)
    }

    @Test
    fun `unknown keys are errors with a suggestion, all reported at once`() {
        val job = write(
            "job.yaml", minimalJob.replace("  user: app_ro", "  usr: app_ro") + "\n" + """
            threads:
              reader: 5
            chunk:
              rowsPerChunk: 1
            """.trimIndent()
        )
        write("s.yaml", "uat: aaaa\nlocal: bbbb\n")
        val msg = problems { loader().load(job) }
        assertContains(msg, "source.usr: unknown key (did you mean 'user'?)")
        assertContains(msg, "threads.reader: unknown key (did you mean 'readers'?)")
        assertContains(msg, "chunk: unknown key")
    }

    @Test
    fun `wrong types and bad values are reported`() {
        val job = write(
            "job.yaml", minimalJob + "\n" + """
            threads:
              readers: many
              writers: 0
            constraints:
              dropForeignKeys: maybe
            tables:
              exclude: [audit_log]
            """.trimIndent()
        )
        write("s.yaml", "uat: aaaa\nlocal: bbbb\n")
        val msg = problems { loader().load(job) }
        assertContains(msg, "threads.readers: expected an integer, got a string")
        assertContains(msg, "threads.writers: must be at least 1")
        assertContains(msg, "constraints.dropForeignKeys: expected true or false")
        assertContains(msg, "tables.exclude[0]: expected <schema>.<table>")
    }

    @Test
    fun `required fields and non-postgres URLs`() {
        val job = write(
            "job.yaml", """
            source:
              url: jdbc:mysql://x/app
            target: {}
            """.trimIndent()
        )
        val msg = problems { loader().load(job) }
        assertContains(msg, "source.url: expected a jdbc:postgresql: URL")
        assertContains(msg, "source.schemas: list at least one schema")
        assertContains(msg, "target.url: required")
    }

    @Test
    fun `target does not accept schemas`() {
        val job = write("job.yaml", minimalJob.replace("  user: postgres", "  user: postgres\n  schemas: [app]"))
        write("s.yaml", "uat: aaaa\nlocal: bbbb\n")
        assertContains(problems { loader().load(job) }, "target.schemas: unknown key")
    }

    @Test
    fun `duplicate keys are errors`() {
        val job = write("job.yaml", minimalJob + "\nsource:\n  url: jdbc:postgresql://x/y\n")
        assertContains(problems { loader().load(job) }, "duplicate key")
    }

    @Test
    fun `secrets are read as plain strings`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: 0123\nlocal: yes\n")
        val config = loader().load(job).config
        assertEquals("0123", config.source.password!!.reveal())
        assertEquals("yes", config.target.password!!.reveal())
    }

    @Test
    fun `secrets YAML errors do not print the offending line`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: \"unterminated-very-secret\nlocal: bbbb\n")
        val msg = problems { loader().load(job) }
        assertContains(msg, "invalid YAML at line")
        assertFalse(msg.contains("unterminated-very-secret"))
    }

    @Test
    fun `nested values in the secrets file are rejected`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat:\n  nested: no\nlocal: bbbb\n")
        assertContains(problems { loader().load(job) }, "'uat' must be a plain string value")
    }

    @Test
    fun `warns when secrets file is readable by group or others`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: aaaa\nlocal: bbbb\n", perms = "rw-r--r--")
        val warnings = loader().load(job).warnings
        assertEquals(1, warnings.size)
        assertContains(warnings[0], "group and others")
        assertContains(warnings[0], "chmod 600")
    }

    @Test
    fun `config hash ignores passwords and secrets location but tracks the rest`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: first-pass\nlocal: bbbb\n")
        write("t.yaml", "uat: second-pass\nlocal: cccc\n")
        val h1 = loader().load(job).config.configHash()
        val h2 = loader().load(job, secretsOverride = "t.yaml").config.configHash()
        assertEquals(h1, h2)

        write("job.yaml", minimalJob.replace("schemas: [app]", "schemas: [app, extra]"))
        assertNotEquals(h1, loader().load(job).config.configHash())
    }

    @Test
    fun `toString of the config never contains password values`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: never-print-me\nlocal: nor-me\n")
        val config = loader().load(job).config
        val text = config.toString()
        assertFalse(text.contains("never-print-me"))
        assertFalse(text.contains("nor-me"))
        assertTrue(text.contains("\${secret:uat}"))
    }

    @Test
    fun `state table defaults to the first source schema and can be overridden without changing the hash`() {
        val job = write("job.yaml", minimalJob)
        write("s.yaml", "uat: aaaa\nlocal: bbbb\n")
        val default = loader().load(job).config
        assertEquals(TableName("app", "pumplet_onegin_state"), default.state.table)

        write("job.yaml", minimalJob.replace("schemas: [app]", "schemas: [AppOwner, other]"))
        val mixed = loader().load(job).config
        assertEquals(TableName("AppOwner", "pumplet_onegin_state"), mixed.state.table)
        assertEquals("\"AppOwner\".\"pumplet_onegin_state\"", mixed.state.table.sql)

        write("job.yaml", minimalJob)

        write("job.yaml", minimalJob + "\nstate:\n  table: ops.copy_state\n")
        val custom = loader().load(job).config
        assertEquals(TableName("ops", "copy_state"), custom.state.table)
        assertEquals(default.configHash(), custom.configHash())
    }

    @Test
    fun `state table name is validated`() {
        write("s.yaml", "uat: aaaa\nlocal: bbbb\n")
        for (bad in listOf("no_schema", "Public.State", "a.b.c", "x.\"quoted\"")) {
            val job = write("job.yaml", minimalJob + "\nstate:\n  table: '$bad'\n")
            assertContains(problems { loader().load(job) }, "state.table: expected <schema>.<table>")
        }
        val job = write("job.yaml", minimalJob + "\nstate:\n  tabel: a.b\n")
        assertContains(problems { loader().load(job) }, "state.tabel: unknown key (did you mean 'table'?)")
    }
}
