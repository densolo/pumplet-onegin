package app.dc8.pumplet.onegin.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.dc8.pumplet.onegin.schema.DiffReport
import app.dc8.pumplet.onegin.state.StateStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

enum class JobStatus(val label: String) {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    /** A diff that found blocking differences (exit code 1 with a report). */
    BLOCKED("blocked"),
    FAILED("failed"),
    /** Stopped by the user (exit code 130); running the command again continues. */
    STOPPED("stopped"),
}

/** What a job page shows: its output, or a richer view of its result. */
enum class JobView { OUTPUT, DIFF_REPORT }

/** One command run from the UI: its output as it arrives, and its outcome. */
class Job(
    val id: Int,
    val profile: String,
    val command: Command,
    val args: List<String>,
    /** The profile's job file, for results written next to it (e.g. the diff report). */
    val jobFile: Path,
    val startedAt: Instant = Instant.now(),
) {
    val lines = mutableStateListOf<OutputLine>()
    var status by mutableStateOf(JobStatus.RUNNING)
        private set
    var exitCode by mutableStateOf<Int?>(null)
        private set
    var endedAt by mutableStateOf<Instant?>(null)
        private set
    /** Diff jobs: the report, read when the job ends (a later diff overwrites the file). */
    var diff by mutableStateOf<DiffReport?>(null)
        private set
    var view by mutableStateOf(JobView.OUTPUT)

    val key get() = id.toString()
    val title get() = "#$id ${command.label}"
    val duration: Duration get() = Duration.between(startedAt, endedAt ?: Instant.now())

    fun finish(code: Int) {
        exitCode = code
        endedAt = Instant.now()
        if (command == Command.DIFF) diff = readDiffReport()
        status = when {
            code == 0 -> JobStatus.SUCCEEDED
            code == 130 || code == 143 -> JobStatus.STOPPED
            command == Command.DIFF && diff?.blocking == true -> JobStatus.BLOCKED
            else -> JobStatus.FAILED
        }
        if (diff != null) view = JobView.DIFF_REPORT
    }

    /** The report this job wrote: `job.yaml` -> `job.diff.json`, if written since the job started. */
    private fun readDiffReport(): DiffReport? {
        val file = jobFile.resolveSibling(jobFile.fileName.toString().substringBeforeLast('.') + ".diff.json")
        if (!Files.exists(file) || Files.getLastModifiedTime(file).toInstant().isBefore(startedAt.minusSeconds(1))) return null
        return runCatching { StateStore.json.readValue(Files.readString(file), DiffReport::class.java) }.getOrNull()
    }
}
