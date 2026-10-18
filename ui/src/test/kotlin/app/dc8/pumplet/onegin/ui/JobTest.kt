package app.dc8.pumplet.onegin.ui

import app.dc8.pumplet.onegin.schema.Comparison
import app.dc8.pumplet.onegin.schema.DiffReport
import app.dc8.pumplet.onegin.schema.Finding
import app.dc8.pumplet.onegin.schema.Severity
import app.dc8.pumplet.onegin.schema.Verdict
import app.dc8.pumplet.onegin.state.StateStore
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JobTest {
    @TempDir
    lateinit var dir: Path

    private fun job(command: Command, startedAt: Instant = Instant.now()) =
        Job(1, "p", command, command.args, dir.resolve("job.yaml"), startedAt)

    private fun writeReport(blocking: Boolean) {
        val r = DiffReport(
            Instant.now(), "h", "src", "dst", emptyList(), emptyList(),
            if (blocking) listOf(Finding(Severity.ERROR, "s.t", "v", "type differs")) else emptyList(),
            listOf(Comparison("s.t", "column", "v", "text", "integer", if (blocking) Verdict.BLOCKER else Verdict.COMPATIBLE)),
        )
        Files.writeString(dir.resolve("job.diff.json"), StateStore.json.writeValueAsString(r))
    }

    @Test
    fun `exit codes map to statuses`() {
        assertEquals(JobStatus.SUCCEEDED, job(Command.STATUS).apply { finish(0) }.status)
        assertEquals(JobStatus.FAILED, job(Command.RUN).apply { finish(1) }.status)
        assertEquals(JobStatus.STOPPED, job(Command.RUN).apply { finish(130) }.status)
        assertEquals(JobStatus.STOPPED, job(Command.COPY).apply { finish(143) }.status)
    }

    @Test
    fun `a diff job keeps its report and opens on it`() {
        writeReport(blocking = true)
        val j = job(Command.DIFF).apply { finish(1) }
        assertEquals(JobStatus.BLOCKED, j.status)
        assertEquals(Verdict.BLOCKER, j.diff!!.comparisons.single().verdict)
        assertEquals(JobView.DIFF_REPORT, j.view)

        writeReport(blocking = false)
        assertEquals(JobStatus.SUCCEEDED, job(Command.DIFF).apply { finish(0) }.status)
    }

    @Test
    fun `an older report is not taken for this job's`() {
        writeReport(blocking = false)
        val j = job(Command.DIFF, startedAt = Instant.now().plusSeconds(60)).apply { finish(1) }
        assertNull(j.diff, "the file predates the job: the diff failed before writing one")
        assertEquals(JobStatus.FAILED, j.status)
        assertEquals(JobView.OUTPUT, j.view)
    }
}
