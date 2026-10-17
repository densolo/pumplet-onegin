package app.dc8.pumplet.onegin.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class CliTest {
    private fun run(vararg args: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val code = runCli(arrayOf(*args), Io(PrintStream(out, true), PrintStream(err, true)))
        return Triple(code, out.toString(), err.toString())
    }

    @Test
    fun `no arguments prints usage`() {
        val (code, _, err) = run()
        assertEquals(2, code)
        assertContains(err, "Usage: pumplet-onegin")
    }

    @Test
    fun `help lists the commands`() {
        val (code, out, _) = run("--help")
        assertEquals(0, code)
        assertContains(out, "validate")
        assertContains(out, "check")
    }

    @Test
    fun `missing --config is a usage error`() {
        val (code, _, err) = run("check")
        assertEquals(2, code)
        assertContains(err, "missing option --config")
        assertContains(err, "Usage: pumplet-onegin check")
    }

    @Test
    fun `positional job file is not accepted`() {
        val (code, _, err) = run("check", "job.yaml", "--config", "j.yaml")
        assertEquals(2, code)
        assertContains(err, "the job file must be passed with --config")
    }

    @Test
    fun `unknown command and option`() {
        val (code, _, err) = run("copy-everything")
        assertEquals(2, code)
        assertContains(err, "no such subcommand copy-everything")
        assertContains(run("check", "--confg", "j.yaml").third, "no such option --confg")
    }

    @Test
    fun `missing job file is a config error`() {
        val (code, _, err) = run("validate", "--config", "/nonexistent/job.yaml")
        assertEquals(1, code)
        assertContains(err, "job file not found")
    }
}
