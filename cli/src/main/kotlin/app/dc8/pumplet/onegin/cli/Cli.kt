package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.ConfigException
import app.dc8.pumplet.onegin.config.ConfigLoader
import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.state.StateException
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.MultiUsageError
import com.github.ajalt.clikt.core.NoSuchArgument
import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicBoolean

/** Exit codes: 0 success, 1 failure (bad config, failed check or stage), 2 usage error. */
object Exit {
    const val OK = 0
    const val FAILED = 1
    const val USAGE = 2
    /** Stopped by the user (128 + SIGINT). */
    const val INTERRUPTED = 130
}

/**
 * Where commands write: [out] for results, [err] for warnings and errors.
 * [interactive]: [out] is a terminal, so progress can be animated.
 * [cancel]: set when the user asks to stop (Ctrl-C); long stages watch it.
 * [progressEverySeconds]: how often a non-interactive copy prints a progress line.
 */
class Io(
    val out: PrintStream,
    val err: PrintStream,
    val interactive: Boolean = false,
    val cancel: AtomicBoolean = AtomicBoolean(),
    val progressEverySeconds: Long = 10,
)

/**
 * Parses [argv], runs the selected command and returns the exit code. Output goes
 * to [io] rather than the process streams so tests can capture it.
 */
fun runCli(argv: Array<String>, io: Io, loader: ConfigLoader = ConfigLoader()): Int {
    val root = PumpletOnegin().subcommands(
        ValidateCommand(io, loader),
        CheckCommand(io, loader),
        DiffCommand(io, loader),
        DisableCommand(io, loader),
        CopyCommand(io, loader),
        EnableCommand(io, loader),
        RecoverCommand(io, loader),
        RunCommand(io, loader),
        FetchTestCommand(io, loader),
        StatusCommand(io, loader),
    )
    return try {
        root.parse(argv)
        Exit.OK
    } catch (e: ProgramResult) {
        e.statusCode
    } catch (e: PrintHelpMessage) {
        // --help prints to stdout; help shown because a command is missing is a usage error.
        val text = root.getFormattedHelp(e)
        if (e.error) io.err.println(text) else io.out.println(text)
        if (e.error) Exit.USAGE else Exit.OK
    } catch (e: PrintMessage) {
        val text = root.getFormattedHelp(e)
        if (e.printError) io.err.println(text) else io.out.println(text)
        if (e.statusCode == 0) Exit.OK else Exit.USAGE
    } catch (e: CliktError) {
        root.getFormattedHelp(e)?.let { io.err.println(it) }
        val errors = if (e is MultiUsageError) e.errors else listOf(e)
        if (errors.any { it is NoSuchArgument }) io.err.println("hint: the job file must be passed with --config <job.yaml>")
        if (e is UsageError || e.statusCode != 0) Exit.USAGE else Exit.OK
    }
}

class PumpletOnegin : CliktCommand(name = "pumplet-onegin") {
    override fun help(context: Context) =
        "Copies data from one PostgreSQL database to another, fast and resumably."

    override fun run() = Unit
}

/** A command that works on a job: takes --config/--secrets and loads them. */
abstract class JobCommand(name: String, protected val io: Io, private val loader: ConfigLoader) :
    CliktCommand(name = name) {

    private val config by option("--config", metavar = "<job.yaml>", help = "job file (required)")
        .path().required()
    private val secrets by option(
        "--secrets", metavar = "<secrets.yaml>", help = "secrets file; overrides 'secrets:' in the job file",
    )
    final override fun run() {
        if (secrets?.isBlank() == true) throw UsageError("--secrets needs a file argument")
        val job = try {
            loader.load(config, secrets)
        } catch (e: ConfigException) {
            io.err.println("error: invalid configuration")
            e.problems.forEach { io.err.println("  - $it") }
            throw ProgramResult(Exit.FAILED)
        }
        job.warnings.forEach { io.err.println("warning: $it") }
        LogFile.attach(job.config)
        log.info("{} started: job {}, hash {}", commandName, job.config.jobFile, job.config.configHash().take(12))
        job.warnings.forEach { log.warn(it) }
        val code = try {
            execute(job.config)
        } catch (e: ProgramResult) {
            log.info("{} finished with exit code {}", commandName, e.statusCode)
            throw e
        } catch (e: StateException) {
            log.error("{} failed: {}", commandName, e.message)
            io.err.println("error: ${job.config.redactor.scrub(e.message)}")
            Exit.FAILED
        } catch (e: java.sql.SQLException) {
            log.error("$commandName failed", e)
            io.err.println("error: ${job.config.redactor.scrub(e.message)}")
            Exit.FAILED
        } catch (e: Exception) {
            log.error("$commandName failed", e)
            io.err.println("error: ${job.config.redactor.scrub(e.toString())}")
            Exit.FAILED
        }
        log.info("{} finished with exit code {}", commandName, code)
        if (code != Exit.OK) throw ProgramResult(code)
    }

    protected val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger("app.dc8.pumplet.onegin.cli")

    /** Runs the command on a loaded, validated job; returns the exit code. */
    protected abstract fun execute(config: JobConfig): Int
}
