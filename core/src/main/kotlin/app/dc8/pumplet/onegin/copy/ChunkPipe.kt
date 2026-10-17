package app.dc8.pumplet.onegin.copy

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streams one chunk's COPY data from its reader to its writer. Holds at most
 * [capacityBytes] (in blocks of about [BLOCK_SIZE]); the reader blocks when it's
 * full, so memory stays bounded however fast the source is. A failure on either
 * side reaches the other: the writer's stream throws, the reader's [put] throws.
 */
class ChunkPipe(capacityBytes: Long) {
    private val queue = ArrayBlockingQueue<ByteArray>((capacityBytes / BLOCK_SIZE).toInt().coerceAtLeast(2))

    @Volatile
    private var failure: Throwable? = null

    /** Writer side: COPY FROM reads this. */
    val input: InputStream = PipeInput()

    /** Reader side: hands over one block. Throws if the writer side has failed. */
    fun put(block: ByteArray) {
        while (true) {
            failure?.let { throw PipeClosedException(it) }
            if (queue.offer(block, 200, TimeUnit.MILLISECONDS)) return
        }
    }

    /** Reader side: no more data. */
    fun finish() = put(END)

    /** Either side: abort the transfer; the other side sees [cause]. */
    fun fail(cause: Throwable) {
        if (failure == null) failure = cause
        queue.clear()
    }

    val failed: Throwable? get() = failure

    private inner class PipeInput : InputStream() {
        private var current: ByteArray? = null
        private var pos = 0
        private var ended = false

        private fun fill(): Boolean {
            while (current == null || pos >= current!!.size) {
                if (ended) return false
                val next = take()
                if (next === END) {
                    ended = true
                    return false
                }
                current = next
                pos = 0
            }
            return true
        }

        private fun take(): ByteArray {
            while (true) {
                failure?.let { throw IOException("source read failed: ${it.message}", it) }
                queue.poll(200, TimeUnit.MILLISECONDS)?.let { return it }
            }
        }

        override fun read(): Int = if (fill()) current!![pos++].toInt() and 0xff else -1

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current!!.size - pos)
            System.arraycopy(current!!, pos, b, off, n)
            pos += n
            return n
        }
    }

    companion object {
        const val BLOCK_SIZE = 256 * 1024
        private val END = ByteArray(0)
    }
}

class PipeClosedException(cause: Throwable) : RuntimeException("transfer aborted: ${cause.message}", cause)
