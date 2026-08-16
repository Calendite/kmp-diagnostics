package dev.diagnostics.stream

import dev.diagnostics.LogLevel
import dev.diagnostics.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire format is a contract with a consumer written in another language, in another repo
 * (the Python logging service). These tests read as that consumer would: field names and shapes
 * are the protocol, and changing one silently is what breaks a debugging session at the worst
 * possible moment.
 */
class LogStreamProtocolTest {

    private fun record(
        id: Long = 1,
        level: LogLevel = LogLevel.DEBUG,
        message: String = "hello",
        data: String? = null,
    ) = LogRecord(
        id = id,
        level = level,
        tag = "TestSubsystem",
        filterTag = "run-1",
        message = message,
        className = "com.example.Thing",
        methodName = "doIt",
        fileName = "Thing.kt",
        line = 42,
        exception = null,
        data = data,
        timestamp = 1_785_410_872_310,
    )

    @Test
    fun everyLineIsExactlyOneNewlineTerminatedJsonObject() {
        val lines = listOf(
            LogStreamProtocol.helloLine("myapp", "Pixel 9", "7F2K"),
            LogStreamProtocol.liveLine(),
            LogStreamProtocol.recordLine(record()),
            LogStreamProtocol.schemaLine(listOf(LogStreamProtocol.TableSchema("events"))),
            LogStreamProtocol.dbInsertLine("events", 41, mapOf("id" to "ev-1", "title" to null)),
        )
        for (line in lines) {
            assertTrue(line.endsWith("\n"), "NDJSON lines are newline-terminated")
            assertEquals(1, line.count { it == '\n' }, "and contain exactly one newline")
            assertTrue(line.startsWith("{"), "each line is a JSON object")
        }
    }

    @Test
    fun helloCarriesTheAppSchemaAndDeviceCode() {
        val hello = LogStreamProtocol.helloLine("myapp", "Pixel 9 Pro XL", "7F2K")
        assertTrue(hello.contains("\"type\":\"hello\""))
        assertTrue(hello.contains("\"app\":\"myapp\""), "the app name is a parameter, not a constant")
        assertTrue(hello.contains("\"schema\":${LogStreamProtocol.SCHEMA_VERSION}"))
        assertTrue(hello.contains("\"device\":\"Pixel 9 Pro XL\""))
        assertTrue(hello.contains("\"device_id\":\"7F2K\""))
    }

    @Test
    fun aRecordUsesTheProtocolsFieldNames() {
        val line = LogStreamProtocol.recordLine(record(id = 4812, level = LogLevel.ERROR, message = "failed"))
        for (field in listOf(
            "\"type\":\"record\"", "\"id\":4812", "\"level\":\"ERROR\"", "\"tag\":\"TestSubsystem\"",
            "\"filter_tag\":\"run-1\"", "\"message\":\"failed\"", "\"class\":\"com.example.Thing\"",
            "\"method\":\"doIt\"", "\"file\":\"Thing.kt\"", "\"line\":42", "\"timestamp\":1785410872310",
        )) {
            assertTrue(line.contains(field), "missing $field in: $line")
        }
    }

    @Test
    fun nullableRecordFieldsArePresentAsExplicitNull() {
        // The consumer's rule: nullable fields are present with null, never missing keys.
        val line = LogStreamProtocol.recordLine(record())
        assertTrue(line.contains("\"exception\":null"))
        assertTrue(line.contains("\"data\":null"))
    }

    @Test
    fun traceLinesOmitInapplicableKeysRatherThanNullingThem() {
        val line = LogStreamProtocol.traceLine(
            LogStreamProtocol.TraceEvent(stage = "session", detail = "started", timestamp = 1L),
        )
        assertTrue(line.contains("\"type\":\"trace\""), "the discriminator must survive encodeDefaults")
        assertTrue(line.contains("\"stage\":\"session\""))
        assertTrue(!line.contains("\"lane\""), "a key that does not apply to this stage is absent")
    }

    @Test
    fun dbRowsAreStringsOrNullSoNoTypeKnowledgeIsNeeded() {
        val line = LogStreamProtocol.dbInsertLine(
            table = "events",
            rowid = 41,
            row = mapOf("id" to "ev-1", "count" to "3", "note" to null),
        )
        assertTrue(line.contains("\"op\":\"insert\""))
        assertTrue(line.contains("\"count\":\"3\""), "numbers arrive as their literal text")
        assertTrue(line.contains("\"note\":null"))
    }
}
