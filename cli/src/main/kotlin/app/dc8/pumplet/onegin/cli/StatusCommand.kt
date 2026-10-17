package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.state.ObjectKind
import app.dc8.pumplet.onegin.state.ObjectStatus
import app.dc8.pumplet.onegin.state.RunInfo
import app.dc8.pumplet.onegin.state.SessionDetail
import app.dc8.pumplet.onegin.state.SessionSummary
import app.dc8.pumplet.onegin.state.StatusReader
import app.dc8.pumplet.onegin.state.TableStatus
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class StatusCommand(io: Io, loader: ConfigLoader) : JobCommand("status", io, loader) {
    override fun help(context: Context) =
        "Show this job's latest session (runs, stages, tables, saved constraints, report) and earlier sessions. " +
            "Read-only."

    private val session by option("--session", metavar = "<id>", help = "show this session (id or unique prefix) instead of the latest")
    private val all by option("--all", help = "list sessions of every job in the state table, not just this one").flag()
    private val limit by option("--limit", metavar = "<n>", help = "how many earlier sessions to list (default 10)")
        .int().restrictTo(min = 0).default(10)

    override fun execute(config: JobConfig): Int {
        val report = StatusReader(config).read(session, all, limit)
        val out = io.out
        out.println("state: ${report.stateTable} on ${report.target}")
        if (!report.tableExists) {
            out.println("no sessions recorded yet (the state table does not exist on the target)")
            return Exit.OK
        }
        if (report.processRunning) out.println("a pumplet-onegin process is working on this target right now")

        val d = report.detail
        if (d == null) out.println("\nno session for this job yet") else detail(d)

        if (report.history.isNotEmpty()) {
            out.println()
            out.println(if (all) "earlier sessions (all jobs):" else "earlier sessions of this job:")
            report.history.forEach { summary(it) }
        }
        return Exit.OK
    }

    private fun detail(d: SessionDetail) {
        val out = io.out
        val s = d.session
        out.println()
        out.println("session ${s.id}  ${s.status}${if (d.sameJob) "" else "  (another job: ${s.jobFile})"}")
        out.println("  created:   ${time(s.createdAt)}")
        s.completedAt?.let { out.println("  completed: ${time(it)}") }
        s.stage?.let { out.println("  stage:     $it") }

        if (d.runs.isNotEmpty()) {
            out.println("  runs:")
            val w = d.runs.maxOf { it.command.length }
            for (r in d.runs) out.println("    #%-3d %s  %s  %-8s %s".format(r.seq, r.command.padEnd(w), time(r.startedAt), took(r), outcome(r)))
        }

        if (d.stages.isNotEmpty()) {
            out.println("  stages:")
            val w = d.stages.maxOf { it.stage.length }
            for (st in d.stages) {
                val extra = st.error?.let { "  error: $it" } ?: st.summary?.let { "  $it" } ?: ""
                out.println("    ${st.stage.padEnd(w)}  %-11s %-8s%s".format(st.status, duration(st.startedAt, st.endedAt), extra))
            }
        }

        if (d.tables.isNotEmpty()) {
            val counts = TableStatus.entries.mapNotNull { st ->
                d.tables.count { it.status == st }.takeIf { it > 0 }?.let { "$it ${st.name.lowercase()}" }
            }
            val rows = d.tables.sumOf { it.rows }
            val bytes = d.tables.sumOf { it.bytes }
            out.println("  tables: ${d.tables.size} (${counts.joinToString(", ")}); copied ${"%,d".format(rows)} rows, ${mb(bytes)}")
            val w = d.tables.maxOf { it.table.length }
            for (t in d.tables) {
                val est = t.estRows?.let { " of ~%,d".format(it) } ?: ""
                val chunks = if (t.chunksTotal > 1) "  chunks ${t.chunksDone}/${t.chunksTotal}" else ""
                val err = t.error?.let { ": $it" } ?: ""
                out.println("    ${t.table.padEnd(w)}  %-7s %,d rows$est$chunks$err".format(t.status, t.rows))
            }
        }

        if (d.objects.isNotEmpty()) {
            val byKind = ObjectKind.entries.mapNotNull { k ->
                d.objects.count { it.kind == k }.takeIf { it > 0 }?.let { "$it ${kindName(k, it)}" }
            }
            val dropped = d.objects.count { it.status == ObjectStatus.DROPPED }
            val pending = d.objects.filter { it.status == ObjectStatus.PENDING }
            val restored = d.objects.count { it.status == ObjectStatus.RESTORED }
            val skipped = d.objects.count { it.status == ObjectStatus.SKIPPED }
            out.println(
                "  saved objects: ${byKind.joinToString(", ")}: $dropped dropped, ${pending.size} pending, $restored restored" +
                    if (skipped > 0) ", $skipped skipped" else ""
            )
            for (o in pending) out.println("    pending ${o.table} ${o.name}${o.error?.let { ": $it" } ?: ""}")
            if (dropped > 0 || pending.isNotEmpty()) {
                out.println("    -> constraints/indexes/triggers are not all in place; run `enable` (or `recover`) to restore them")
            }
        }

        d.report?.let { r ->
            out.println("  report (run #${r.runSeq}): ${"%,d".format(r.totalRows)} rows, ${mb(r.totalBytes)} in ${ms(r.durationMs)}")
            if (r.stageDurationsMs.isNotEmpty()) {
                out.println("    stages: ${r.stageDurationsMs.entries.joinToString(", ") { "${it.key} ${ms(it.value)}" }}")
            }
            val w = r.tables.maxOfOrNull { it.table.length } ?: 0
            for (t in r.tables) {
                val secs = t.durationMs.coerceAtLeast(1) / 1000.0
                out.println(
                    "    ${t.table.padEnd(w)}  %,d rows  %s  %s  %,.0f rows/s  %.1f MB/s".format(
                        t.rows, mb(t.bytes), ms(t.durationMs), t.rows / secs, t.bytes / secs / MB,
                    )
                )
            }
        }
    }

    private fun summary(h: SessionSummary) {
        val s = h.session
        val last = h.lastRun?.let { "last run #${it.seq} ${it.command} ${time(it.startedAt)} ${outcome(it)}" } ?: "no runs"
        val job = if (h.sameJob) "" else "  [${s.jobFile}]"
        io.out.println("  ${s.id.take(8)}  %-10s created ${time(s.createdAt)}  $last$job".format(s.status))
    }

    private fun outcome(r: RunInfo) = r.status.name + (r.error?.let { ": $it" } ?: "")

    private fun took(r: RunInfo) = duration(r.startedAt, r.endedAt)

    private fun duration(from: Instant, to: Instant?) = to?.let { ms(Duration.between(from, it).toMillis()) } ?: "-"

    private fun kindName(k: ObjectKind, n: Int) = when (k) {
        ObjectKind.FOREIGN_KEY -> if (n == 1) "foreign key" else "foreign keys"
        ObjectKind.INDEX -> if (n == 1) "index" else "indexes"
        ObjectKind.TRIGGER -> if (n == 1) "trigger" else "triggers"
    }

    companion object {
        private const val MB = 1024.0 * 1024.0
        private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

        fun time(i: Instant): String = TIME.format(i)

        fun mb(bytes: Long) = "%.1f MB".format(bytes / MB)

        /** 950ms, 12s, 4m10s, 1h02m. */
        fun ms(millis: Long): String {
            val s = millis / 1000
            return when {
                millis < 1000 -> "${millis}ms"
                s < 60 -> "${s}s"
                s < 3600 -> "${s / 60}m%02ds".format(s % 60)
                else -> "${s / 3600}h%02dm".format(s % 3600 / 60)
            }
        }
    }
}
