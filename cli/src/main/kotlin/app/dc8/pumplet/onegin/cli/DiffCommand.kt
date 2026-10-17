package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.schema.DiffReport
import app.dc8.pumplet.onegin.schema.Differ
import app.dc8.pumplet.onegin.schema.Severity
import app.dc8.pumplet.onegin.schema.TableAction
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class DiffCommand(io: Io, loader: ConfigLoader) : JobCommand("diff", io, loader) {
    override fun help(context: Context) =
        "Compare source and target schemas and show what would be copied. Read-only on both sides; " +
            "also writes the report as JSON next to the job file."

    private val verbose by option("-v", "--verbose", help = "also list INFO findings and every copied column").flag()

    override fun execute(config: JobConfig): Int {
        val differ = Differ(config)
        val report = differ.run()
        render(report)
        val path = differ.write(report)
        io.out.println()
        io.out.println("report written to $path")
        io.out.println(verdict(report))
        return if (report.blocking) Exit.FAILED else Exit.OK
    }

    private fun render(r: DiffReport) {
        val out = io.out
        out.println("source: ${r.source}")
        out.println("target: ${r.target}")

        val counts = TableAction.entries.mapNotNull { a ->
            r.tables.count { it.action == a }.takeIf { it > 0 }?.let { "$it ${a.name.lowercase()}" }
        }
        out.println()
        out.println("tables: ${r.tables.size} selected (${counts.joinToString(", ").ifEmpty { "none" }})")
        val w = r.tables.maxOfOrNull { it.table.length } ?: 0
        for (t in r.tables) {
            val detail = when (t.action) {
                TableAction.COPY -> buildString {
                    append("${t.columns.size} columns")
                    append(if (t.estRows < 0) ", never analyzed" else ", ~%,d rows".format(t.estRows))
                    t.chunks?.let { append(", $it chunks") }
                    if (!t.binary) append(", text format")
                    if (t.largeObjects) append(", large objects")
                }
                else -> t.reason.orEmpty()
            }
            out.println("  ${t.table.padEnd(w)}  %-7s %s".format(t.action, detail))
            if (verbose && t.action == TableAction.COPY) {
                out.println("    ${" ".repeat(w)}  columns: ${t.columns.joinToString(", ") { it.name }}")
            }
        }

        val shown = r.findings.filter { verbose || it.severity != Severity.INFO }
        if (shown.isNotEmpty()) {
            out.println()
            out.println("findings:")
            for (f in shown.sortedWith(compareBy({ it.severity }, { it.table }, { it.subject }))) {
                val where = f.table + (f.subject?.let { ".$it" } ?: "")
                out.println("  %-8s %s: %s".format(f.severity, where, f.message))
            }
        }
        val hidden = r.findings.size - shown.size
        if (hidden > 0) out.println("  ($hidden info finding(s) hidden; use --verbose)")
    }

    private fun verdict(r: DiffReport) = when {
        r.blocking -> "diff found ${r.errors} blocking error(s) and ${r.warnings} warning(s)"
        r.warnings > 0 -> "diff OK with ${r.warnings} warning(s)"
        else -> "diff OK"
    }
}
