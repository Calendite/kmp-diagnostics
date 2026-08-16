package dev.diagnostics

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private enum class QueueTags(override val tag: String) : LogTag { SUBSYSTEM("Queue") }

private class FakeStore(private val failOn: Int? = null) : LogStore {
    val inserted = mutableListOf<LogEvent>()
    var deletedBefore: Long? = null
    private var count = 0

    override suspend fun insert(event: LogEvent): Long {
        count++
        if (count == failOn) throw IllegalStateException("storage is unavailable")
        inserted += event
        return count.toLong()
    }

    override suspend fun deleteOlderThan(cutoffEpochMillis: Long) {
        deletedBefore = cutoffEpochMillis
    }
}

private fun event(message: String) = LogEvent(
    level = LogLevel.DEBUG,
    tag = "Queue",
    message = message,
    filterTag = null,
    callSite = CallSite.UNKNOWN,
    throwable = null,
    data = null,
    timestampEpochMillis = 1_000,
)

/**
 * The queue's job is to make logging free at the call site and safe under failure: never block,
 * never lose order, never let a broken store take the application down, and never accumulate.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueueingSinkTest {

    @Test
    fun recordsQueuedBeforeStorageExistsAreStillWritten() = runTest {
        val store = FakeStore()
        val sink = QueueingSink(store, now = { 10_000 }, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        // Emitted before start(): the gap between process launch and the database opening.
        sink.emit(event("early one"))
        sink.emit(event("early two"))
        sink.start()
        advanceUntilIdle()

        assertEquals(listOf("early one", "early two"), store.inserted.map { it.message })
    }

    @Test
    fun expiredRecordsAreDroppedOnStart() = runTest {
        val store = FakeStore()
        QueueingSink(store, retentionMillis = 500, now = { 10_000 }, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))).start()
        advanceUntilIdle()

        assertEquals(9_500, store.deletedBefore, "cutoff is now minus the retention window")
    }

    @Test
    fun aFailedWriteIsReportedAndTheConsumerKeepsGoing() = runTest {
        val failures = mutableListOf<Throwable>()
        val store = FakeStore(failOn = 1)
        val sink = QueueingSink(store, now = { 0 }, onStorageFailure = { failures += it }, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        sink.start()
        sink.emit(event("doomed"))
        sink.emit(event("fine"))
        advanceUntilIdle()

        assertEquals(1, failures.size, "the failure is reported, not logged — logging would recurse")
        assertEquals(listOf("fine"), store.inserted.map { it.message }, "and the queue survives it")
    }

    @Test
    fun liveRecordsCarryTheirStoredId() = runTest {
        val store = FakeStore()
        val sink = QueueingSink(store, now = { 0 }, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))
        val seen = mutableListOf<LogRecord>()

        // Subscribe first: the tap is a hot flow with no replay, so a record emitted before the
        // collector arrives is legitimately missed — that is the "slow consumer loses live lines"
        // rule the wire protocol documents, not a defect.
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            sink.liveRecords.collect { seen += it }
        }
        sink.start()
        sink.emit(event("live"))
        advanceUntilIdle()
        collector.cancel()

        val record = seen.single()
        assertEquals("live", record.message)
        assertTrue(record.id > 0, "the live tap carries the real row id, not a placeholder")
    }

    @Test
    fun anOverfullQueueDropsItsOldestRatherThanBlocking() = runTest {
        val store = FakeStore()
        val sink = QueueingSink(store, queueCapacity = 4, now = { 0 }, scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        repeat(100) { sink.emit(event("record $it")) }
        sink.start()
        advanceUntilIdle()

        assertTrue(store.inserted.size <= 4, "the queue is bounded")
        assertTrue(
            store.inserted.last().message == "record 99",
            "and it keeps the newest, having dropped the oldest",
        )
    }
}
