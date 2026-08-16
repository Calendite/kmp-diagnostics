package dev.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/** Durable storage for records. Implemented per application, over whatever database it already has. */
interface LogStore {
    /** Persists [event] and returns the id it was given. */
    suspend fun insert(event: LogEvent): Long

    /** Drops records older than [cutoffEpochMillis], so storage cannot grow without bound. */
    suspend fun deleteOlderThan(cutoffEpochMillis: Long)
}

/**
 * A [DiagnosticSink] that never blocks its caller: records go into a bounded queue on the calling
 * thread and a single consumer drains them into a [LogStore] in order.
 *
 * The queue also covers the gap before storage exists — early-startup records wait in it and are
 * written once [start] runs. If storage never arrives, the queue simply drops its oldest records
 * and nothing accumulates.
 *
 * Storage failures are reported to [onStorageFailure] rather than logged, because logging from
 * inside a sink recurses.
 *
 * [liveRecords] is every record as it lands, carrying its real id — what a live stream consumes.
 * A slow or absent subscriber never blocks the consumer; its buffer drops the oldest.
 */
class QueueingSink(
    private val store: LogStore,
    private val retentionMillis: Long = DEFAULT_RETENTION_MILLIS,
    private val queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val now: () -> Long = ::epochMillis,
    private val onStorageFailure: (Throwable) -> Unit = {},
    // Injectable so tests can drive the consumer on their own scheduler; production wants a
    // long-lived background scope that a failed write cannot tear down.
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : DiagnosticSink {

    private val pending = Channel<LogEvent>(queueCapacity, BufferOverflow.DROP_OLDEST)
    private var started = false

    private val liveTap = MutableSharedFlow<LogRecord>(
        extraBufferCapacity = queueCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val liveRecords: SharedFlow<LogRecord> get() = liveTap

    override fun emit(event: LogEvent) {
        pending.trySend(event)
    }

    /** Starts draining the queue into the store. Later calls are ignored; the first one wins. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            guard { store.deleteOlderThan(now() - retentionMillis) }
            for (event in pending) {
                guard {
                    val id = store.insert(event)
                    liveTap.tryEmit(event.toRecord(id))
                }
            }
        }
    }

    private inline fun guard(write: () -> Unit) {
        try {
            write()
        } catch (failure: Throwable) {
            onStorageFailure(failure)
        }
    }

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 256
        const val DEFAULT_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}
