package dev.diagnostics.stream

import dev.diagnostics.LogRecord
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The wire format of the diagnostic stream (schema 1).
 *
 * NDJSON: every message is one JSON object on one line, UTF-8, `\n`-terminated. A consumer needs a
 * socket and a per-line JSON parser, nothing more. The `type` field discriminates; unknown types
 * must be skipped, not treated as errors, so the schema can grow without breaking old consumers.
 *
 * Kept in commonMain, away from any socket, so the encoding is unit-testable and the documented
 * schema has exactly one source of truth.
 */
object LogStreamProtocol {

    /** Bump when a change would break a consumer; additive fields do not count. */
    const val SCHEMA_VERSION = 1

    private val json = Json { encodeDefaults = true }

    /**
     * First line on every connection: what this is, and whether the consumer can read it.
     *
     * [deviceCode] is the stable per-install code shown on the Developer Options row; the logging
     * service scopes a window to one device by asking the user to type it. It also makes
     * `(device_id, id)` a globally unique record key, since every device's `logs.id` starts at 1.
     */
    fun helloLine(appName: String, deviceName: String, deviceCode: String): String =
        json.encodeToString(Hello(app = appName, device = deviceName, deviceId = deviceCode)) + "\n"

    /** Sent after the history replay: everything from here on happened after you connected. */
    fun liveLine(): String = """{"type":"live"}""" + "\n"

    fun recordLine(record: LogRecord): String =
        json.encodeToString(record.toWire()) + "\n"

    /**
     * Sent once after `hello`: every observed table and which of its columns reference which other
     * table. This is what lets a consumer draw row-to-row lines without knowing the app: a table is
     * only *rendered* when its first `db` line arrives, but the references must be known up front.
     */
    fun schemaLine(tables: List<TableSchema>): String =
        json.encodeToString(WireSchema(tables = tables)) + "\n"

    /**
     * One inserted row. Every value is a string (or null): numbers arrive as their literal text and
     * BLOBs arrive pre-summarised, so a consumer never needs per-column type knowledge.
     */
    fun dbInsertLine(table: String, rowid: Long, row: Map<String, String?>): String =
        json.encodeToString(WireDbChange(op = "insert", table = table, rowid = rowid, row = row)) + "\n"

    /**
     * One sharing-trace event (`Sharing_Trace_View.md` in the logging service repo). Unlike
     * `record`/`db` lines, keys that do not apply to a stage are **absent**, not null: each stage
     * has a fixed key set and consumers discriminate on [stage].
     */
    fun traceLine(event: TraceEvent): String = traceJson.encodeToString(event) + "\n"

    @Serializable
    data class TraceEvent(
        val type: String = "trace",
        val stage: String,
        val series: String? = null,
        val lane: String? = null,
        val seq: Int? = null,
        val summary: String? = null,
        val hidden: Boolean? = null,
        val deleted: Boolean? = null,
        @SerialName("field_hash") val fieldHash: String? = null,
        @SerialName("cipher_hash") val cipherHash: String? = null,
        val size: Int? = null,
        val outcome: String? = null,
        val op: String? = null,
        val phase: String? = null,
        val detail: String? = null,
        /** Session lines only: how many seek attempts this round has made at the peer so far. */
        val attempts: Int? = null,
        /**
         * `lanes` stage only: this device's view of every lane as `"count/head8"`. The consumer
         * compares the two phones' copies against each other and against the relay's, which is
         * the only way to see at a glance which of the three is out of step.
         */
        val lanes: Map<String, String>? = null,
        val timestamp: Long,
    )

    // encodeDefaults matters: without it TraceEvent.type ("trace") counts as a default value and is
    // silently dropped from the wire, and the service discards every trace line as untyped. That
    // one missing key is what made the Sharing Trace page show nothing while everything else worked.
    private val traceJson = Json {
        explicitNulls = false
        encodeDefaults = true
    }

    /** A column of [table] that references [refTable]. Declared FKs and known soft references alike. */
    @Serializable
    data class ColumnRef(
        val column: String,
        @SerialName("ref_table") val refTable: String,
        @SerialName("ref_column") val refColumn: String,
    )

    @Serializable
    data class TableSchema(
        val table: String,
        val refs: List<ColumnRef> = emptyList(),
    )

    @Serializable
    private data class WireSchema(
        val type: String = "schema",
        val tables: List<TableSchema>,
    )

    @Serializable
    private data class WireDbChange(
        val type: String = "db",
        val op: String,
        val table: String,
        val rowid: Long,
        val row: Map<String, String?>,
    )

    @Serializable
    private data class Hello(
        val type: String = "hello",
        val app: String,
        val schema: Int = SCHEMA_VERSION,
        val device: String,
        @SerialName("device_id") val deviceId: String,
    )

    /**
     * One log record on the wire. Field names are part of the protocol; the columns of the `logs`
     * table under their storage names, plus `type`.
     */
    @Serializable
    private data class WireRecord(
        val type: String = "record",
        val id: Long,
        val level: String,
        val tag: String,
        @SerialName("filter_tag") val filterTag: String?,
        val message: String,
        @SerialName("class") val className: String?,
        val method: String?,
        val file: String?,
        val line: Int?,
        val exception: String?,
        /** Additive in schema 1: payloads too big for `message` that are not stack traces. */
        val data: String?,
        val timestamp: Long,
    )

    private fun LogRecord.toWire() = WireRecord(
        id = id,
        level = level.name,
        tag = tag,
        filterTag = filterTag,
        message = message,
        className = className,
        method = methodName,
        file = fileName,
        line = line,
        exception = exception,
        data = data,
        timestamp = timestamp,
    )
}
