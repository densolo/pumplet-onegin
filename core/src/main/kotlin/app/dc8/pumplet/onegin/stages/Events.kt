package app.dc8.pumplet.onegin.stages

import app.dc8.pumplet.onegin.state.SavedObject
import app.dc8.pumplet.onegin.state.Stage

/** Progress reported by the stages, for a front end to show. */
sealed interface Event {
    data class StageStarted(val stage: Stage) : Event
    data class StageFinished(val stage: Stage, val summary: String?) : Event

    /**
     * A constraint, index or trigger was dropped/disabled, restored, or failed to restore.
     * [detail]: for a failure, the failed SQL (and the stack trace if it wasn't a data problem).
     */
    data class ObjectChanged(val obj: SavedObject, val detail: String? = null) : Event
    /** A table's copy (or fetch) begins; [chunksDone] were committed by earlier runs. */
    data class TableStarted(val table: String, val estRows: Long, val chunks: Int, val chunksDone: Int, val rowsDone: Long) : Event

    /** More data streamed within chunk [index] since the last event; [rows] is approximate (exact in [ChunkDone]). */
    data class ChunkProgress(val table: String, val index: Int, val rows: Long, val bytes: Long) : Event
    data class ChunkDone(val table: String, val index: Int, val rows: Long, val bytes: Long) : Event
    /** [detail]: on the last attempt, the failed SQL with where it ran and the stack trace. */
    data class ChunkFailed(val table: String, val index: Int, val attempt: Int, val error: String, val retrying: Boolean, val detail: String? = null) : Event
    data class TableFinished(val table: String, val ok: Boolean, val rows: Long, val bytes: Long, val durationMs: Long, val error: String?) : Event

    data class Info(val message: String) : Event
    data class Warning(val message: String) : Event
}

fun interface EventSink {
    fun emit(event: Event)

    companion object {
        val NONE = EventSink { }
    }
}
