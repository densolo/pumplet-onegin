package app.dc8.pumplet.onegin.cli

import com.github.ajalt.mordant.terminal.Terminal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val io = Io(System.out, System.err, interactive = runCatching { Terminal().terminalInfo.outputInteractive }.getOrDefault(false))
    val finished = CountDownLatch(1)
    // Ctrl-C: ask the running stage to stop (in-flight chunks roll back), and give it time
    // to leave the state consistent before the JVM exits.
    Runtime.getRuntime().addShutdownHook(Thread {
        if (finished.count == 0L) return@Thread
        io.cancel.set(true)
        io.err.println("\nstopping: rolling back chunks in flight...")
        finished.await(60, TimeUnit.SECONDS)
    })
    val code = try {
        runCli(args, io)
    } finally {
        finished.countDown()
    }
    exitProcess(code)
}
