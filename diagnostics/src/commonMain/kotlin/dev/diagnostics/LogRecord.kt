package dev.diagnostics

/**
 * A stored record: a [LogEvent] that has been persisted and therefore has an id.
 *
 * [tag] is the string rather than a [LogTag] because stored records outlive the constants that
 * produced them — a tag can be renamed or removed and old records must still read back.
 */
data class LogRecord(
    val id: Long,
    val level: LogLevel,
    val tag: String,
    val filterTag: String?,
    val message: String,
    val className: String?,
    val methodName: String?,
    val fileName: String?,
    val line: Int?,
    val exception: String?,
    /** A payload too big for [message] that is not a stack trace: JSON, a serialised object. */
    val data: String?,
    val timestamp: Long,
)

/** What a stored view is narrowed to. Null means "not filtering on this". */
data class LogQuery(
    val tag: String? = null,
    val filterTag: String? = null,
    val levels: Set<LogLevel> = LogLevel.entries.toSet(),
    val sinceEpochMillis: Long = 0L,
)

/** Turns an emitted event into a record once storage has assigned it an id. */
fun LogEvent.toRecord(id: Long): LogRecord = LogRecord(
    id = id,
    level = level,
    tag = tag,
    filterTag = filterTag,
    message = message,
    className = callSite.className,
    methodName = callSite.methodName,
    fileName = callSite.fileName,
    line = callSite.line,
    exception = throwable?.stackTraceToString(),
    data = data,
    timestamp = timestampEpochMillis,
)
