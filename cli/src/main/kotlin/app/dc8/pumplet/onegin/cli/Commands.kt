package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.config.Redactor
import app.dc8.pumplet.onegin.db.Checker
import app.dc8.pumplet.onegin.db.Connected
import com.github.ajalt.clikt.core.Context

class ValidateCommand(io: Io, loader: ConfigLoader) : JobCommand("validate", io, loader) {
    override fun help(context: Context) = "Load and validate the job and secrets files."

    override fun execute(config: JobConfig): Int {
        io.out.println("configuration OK: ${config.jobFile}")
        io.out.println("  secrets: ${config.secretsFile ?: "(none)"}")
        io.out.println("  source:  ${Redactor.safeJdbcUrl(config.source.url)}")
        io.out.println("  target:  ${Redactor.safeJdbcUrl(config.target.url)}")
        io.out.println("  hash:    ${config.configHash()}")
        return Exit.OK
    }
}

class CheckCommand(io: Io, loader: ConfigLoader) : JobCommand("check", io, loader) {
    override fun help(context: Context) =
        "Validate, then connect to source and target and report what would be copied."

    override fun execute(config: JobConfig): Int {
        val report = Checker(config).run()
        val out = io.out
        fun problem(msg: String) = out.println("  PROBLEM: $msg")

        val src = report.source
        connection("source", src.connection)
        if (src.connection.error == null) {
            src.missingSchemas.forEach { problem("source schema '$it' does not exist") }
            out.println("  tables: ${src.selected.size} selected of ${src.totalTables} in schemas ${src.schemas.joinToString(", ")}")
            val width = src.selected.maxOfOrNull { it.qualified.length } ?: 0
            for (t in src.selected) {
                val rows = if (t.estRows < 0) "never analyzed" else "~%,d rows".format(t.estRows)
                val chunks = t.chunks?.let { "  -> $it chunks" } ?: ""
                out.println("    ${t.qualified.padEnd(width)}  $rows$chunks")
                if (!t.readable) problem("source user has no SELECT privilege on ${t.qualified}")
            }
            if (src.excluded.isNotEmpty()) out.println("  excluded: ${src.excluded.joinToString(", ")}")
            if (src.noMatch) problem("no source tables match tables.include/exclude")
        }

        val dst = report.target
        connection("target", dst.connection)
        if (dst.connection.error == null) {
            out.println(
                if (src.connection.error != null) "  (tables not compared: the source isn't reachable)"
                else "  selected tables already present on target: ${dst.alreadyPresent} of ${dst.selectedCount}"
            )
        }

        out.println()
        out.println(if (report.ok) "check OK" else "check found ${report.problems.size} problem(s)")
        return if (report.ok) Exit.OK else Exit.FAILED
    }

    private fun connection(role: String, c: Connected) {
        io.out.println()
        io.out.println("$role: ${c.description}")
        if (c.error != null) io.out.println("  PROBLEM: ${c.error}")
        else io.out.println("  connected: ${c.serverVersion}")
    }
}
