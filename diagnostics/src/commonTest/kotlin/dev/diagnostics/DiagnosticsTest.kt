package dev.diagnostics

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private enum class TestTags(override val tag: String) : LogTag {
    SUBSYSTEM("TestSubsystem"),
}

private class RecordingSink : DiagnosticSink {
    val events = mutableListOf<LogEvent>()
    override fun emit(event: LogEvent) {
        events += event
    }
}

/**
 * The guarantee this library exists to keep: **nothing a log call is given is evaluated unless a
 * sink is installed.** Not the message, not the payload — so a developer-only line interpolating
 * key material cannot materialise that material in a build where nothing is listening.
 */
class DiagnosticsTest {

    @AfterTest
    fun cleanUp() = Diagnostics.uninstall()

    @Test
    fun withNoSinkNothingIsEvaluatedAtAll() {
        Diagnostics.uninstall()
        var messageBuilt = false
        var dataBuilt = false

        Diagnostics.debug(TestTags.SUBSYSTEM, data = { dataBuilt = true; "payload" }) {
            messageBuilt = true
            "derived key for someone"
        }
        Diagnostics.warning(TestTags.SUBSYSTEM) { messageBuilt = true; "..." }
        Diagnostics.error(TestTags.SUBSYSTEM) { messageBuilt = true; "..." }

        assertFalse(messageBuilt, "an uninstalled logger must never build its message")
        assertFalse(dataBuilt, "nor its payload")
        assertFalse(Diagnostics.isInstalled)
    }

    /**
     * The same property stated the way it will actually fail: a message that *cannot* be built
     * without throwing proves the lambda was never invoked. If this ever regresses, the exception
     * arrives at an unrelated call site — which is exactly how a silent string materialisation
     * would first be noticed in the wild.
     */
    @Test
    fun anExplodingMessageIsHarmlessWhileUninstalled() {
        Diagnostics.uninstall()
        Diagnostics.debug(TestTags.SUBSYSTEM) { error("this must never run") }
        Diagnostics.debug(TestTags.SUBSYSTEM, data = { error("nor this") }) { "safe" }
    }

    @Test
    fun anInstalledSinkReceivesEverythingTheCallSiteGave() {
        val sink = RecordingSink()
        Diagnostics.install(sink)

        val boom = IllegalStateException("nope")
        Diagnostics.error(
            TestTags.SUBSYSTEM,
            filterTag = "pairing-a3",
            throwable = boom,
            data = { mapOf("lane" to "device-1") },
        ) { "ceremony failed" }

        val event = sink.events.single()
        assertEquals(LogLevel.ERROR, event.level)
        assertEquals("TestSubsystem", event.tag)
        assertEquals("ceremony failed", event.message)
        assertEquals("pairing-a3", event.filterTag)
        assertEquals(boom, event.throwable)
        assertEquals("{lane=device-1}", event.data)
        assertTrue(event.timestampEpochMillis > 0)
    }

    @Test
    fun payloadsAreStringifiedAtTheCallSiteNotLater() {
        val sink = RecordingSink()
        Diagnostics.install(sink)
        val mutable = mutableListOf("first")

        Diagnostics.debug(TestTags.SUBSYSTEM, data = { mutable }) { "before" }
        mutable += "second"

        assertEquals("[first]", sink.events.single().data, "what was recorded is what it said then")
    }

    @Test
    fun uninstallingStopsDeliveryImmediately() {
        val sink = RecordingSink()
        Diagnostics.install(sink)
        Diagnostics.debug(TestTags.SUBSYSTEM) { "one" }
        Diagnostics.uninstall()
        Diagnostics.debug(TestTags.SUBSYSTEM) { "two" }

        assertEquals(listOf("one"), sink.events.map { it.message })
    }

    @Test
    fun aRecordCarriesItsEventFaithfully() {
        val sink = RecordingSink()
        Diagnostics.install(sink)
        Diagnostics.warning(TestTags.SUBSYSTEM) { "careful" }

        val record = sink.events.single().toRecord(id = 42)
        assertEquals(42L, record.id)
        assertEquals("careful", record.message)
        assertEquals("TestSubsystem", record.tag)
        assertNull(record.exception)
    }
}
