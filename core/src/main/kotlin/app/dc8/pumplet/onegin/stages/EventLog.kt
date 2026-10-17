package app.dc8.pumplet.onegin.stages

import org.slf4j.LoggerFactory

/** Writes every event to the log (except per-block progress), then passes it on to [next]. */
class LoggingEventSink(private val next: EventSink = EventSink.NONE) : EventSink {
    private val log = LoggerFactory.getLogger("app.dc8.pumplet.onegin.events")

    override fun emit(event: Event) {
        when (event) {
            is Event.StageStarted -> log.info("stage {} started", event.stage.label)
            is Event.StageFinished -> log.info("stage {} finished: {}", event.stage.label, event.summary ?: "done")
            is Event.ObjectChanged -> event.obj.let {
                log.info("{} {} {}.{}{}", it.status.name.lowercase(), it.kind.name.lowercase(), it.table, it.name, it.error?.let { e -> ": $e" } ?: "")
            }
            is Event.TableStarted -> log.info(
                "table {} started: ~{} rows, {} chunk(s), {} committed earlier", event.table, event.estRows, event.chunks, event.chunksDone,
            )
            is Event.ChunkProgress -> Unit
            is Event.ChunkDone -> log.info("table {} chunk {} committed: {} rows, {} bytes", event.table, event.index + 1, event.rows, event.bytes)
            is Event.ChunkFailed -> log.warn(
                "table {} chunk {} attempt {} failed{}: {}", event.table, event.index + 1, event.attempt,
                if (event.retrying) " (retrying)" else "", event.error,
            )
            is Event.TableFinished ->
                if (event.ok) log.info("table {} done: {} rows, {} bytes in {} ms", event.table, event.rows, event.bytes, event.durationMs)
                else log.error("table {} failed: {}", event.table, event.error)
            is Event.Info -> log.info(event.message)
            is Event.Warning -> log.warn(event.message)
        }
        next.emit(event)
    }
}
