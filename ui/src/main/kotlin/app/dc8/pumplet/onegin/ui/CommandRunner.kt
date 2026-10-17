package app.dc8.pumplet.onegin.ui

import app.dc8.pumplet.onegin.cli.Io
import app.dc8.pumplet.onegin.cli.runCli
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One line of command output. */
data class OutputLine(val text: String, val stream: Stream) {
    enum class Stream { OUT, ERR, INFO }
}

/**
 * Runs CLI commands in-process on a background thread, exactly as the command line
 * would, with their output delivered line by line to [onLine] (from that thread).
 * One command at a time: the CLI's log-file setup is global to the process.
 */
class CommandRunner(private val onLine: (String, OutputLine) -> Unit, private val onFinished: (String, Int) -> Unit) {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pumplet-command").apply { isDaemon = true } }
    private val current = AtomicReference<Running?>()

    private class Running(val profile: String, val cancel: AtomicBoolean)

    val busy: Boolean get() = current.get() != null

    /** Starts `pumplet-onegin <args>` for [profile]; false if another command is still running. */
    fun start(profile: String, args: List<String>): Boolean {
        val run = Running(profile, AtomicBoolean())
        if (!current.compareAndSet(null, run)) return false
        executor.execute {
            val out = PrintStream(LineStream { onLine(profile, OutputLine(it, OutputLine.Stream.OUT)) }, true, Charsets.UTF_8)
            val err = PrintStream(LineStream { onLine(profile, OutputLine(it, OutputLine.Stream.ERR)) }, true, Charsets.UTF_8)
            onLine(profile, OutputLine("$ pumplet-onegin ${args.joinToString(" ")}", OutputLine.Stream.INFO))
            val code = try {
                runCli(args.toTypedArray(), Io(out, err, interactive = false, cancel = run.cancel, progressEverySeconds = 2))
            } catch (e: Throwable) {
                err.println("error: $e")
                1
            } finally {
                out.flush()
                err.flush()
            }
            onLine(profile, OutputLine("exit code $code", OutputLine.Stream.INFO))
            current.set(null)
            onFinished(profile, code)
        }
        return true
    }

    /** Asks the running command to stop, as Ctrl-C does: a copy rolls back its in-flight chunks. */
    fun stop() {
        current.get()?.cancel?.set(true)
    }

    /** Splits written bytes into lines (UTF-8), passing each complete line on. */
    private class LineStream(private val line: (String) -> Unit) : OutputStream() {
        private val buf = ByteArrayOutputStream()

        @Synchronized
        override fun write(b: Int) {
            if (b == '\n'.code) emit() else buf.write(b)
        }

        @Synchronized
        override fun write(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) write(b[i].toInt())
        }

        @Synchronized
        override fun flush() {
            if (buf.size() > 0) emit()
        }

        private fun emit() {
            line(buf.toString(Charsets.UTF_8).trimEnd('\r'))
            buf.reset()
        }
    }
}
