package app.dc8.pumplet.onegin.cli

import app.dc8.pumplet.onegin.config.JobConfig
import app.dc8.pumplet.onegin.config.Redactor
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.PatternLayout
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.FileAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * The job's log, next to the job file (`job.yaml` -> `job.log`), appended to on every
 * command. Gets the tool's own logging at INFO and libraries' warnings; the console
 * keeps showing only progress and important messages. Every line passes through the
 * job's redactor, so a secret that reached a message or stack trace is masked.
 */
object LogFile {
    private const val APPENDER = "pumplet-job-log"

    fun path(config: JobConfig): Path {
        val job = config.jobFile
        return job.resolveSibling(job.fileName.toString().substringBeforeLast('.') + ".log")
    }

    fun attach(config: JobConfig): Path? {
        val ctx = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
        val path = path(config)
        val layout = RedactingLayout(config.redactor).apply {
            context = ctx
            pattern = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{0}: %msg%n"
            start()
        }
        val appender = FileAppender<ILoggingEvent>().apply {
            context = ctx
            name = APPENDER
            file = path.toString()
            isAppend = true
            encoder = LayoutWrappingEncoder<ILoggingEvent>().apply { context = ctx; this.layout = layout; start() }
            start()
        }
        val root = ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
        root.getAppender(APPENDER)?.let { root.detachAppender(it); it.stop() }
        root.addAppender(appender)
        // The tool's own messages go to the file only; the console has its own output.
        ctx.getLogger("app.dc8").apply {
            level = Level.INFO
            isAdditive = false
            getAppender(APPENDER)?.let { detachAppender(it); it.stop() }
            addAppender(appender)
        }
        return path
    }

    /** Stops the file appender (tests call [attach] many times in one JVM). */
    fun detach() {
        val ctx = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
        for (name in listOf(org.slf4j.Logger.ROOT_LOGGER_NAME, "app.dc8")) {
            val logger = ctx.getLogger(name)
            logger.getAppender(APPENDER)?.let { logger.detachAppender(it); it.stop() }
        }
    }

    private class RedactingLayout(private val redactor: Redactor) : PatternLayout() {
        override fun doLayout(event: ILoggingEvent): String = redactor.scrub(super.doLayout(event))
    }
}
