package dev.diagnostics

/** Severity of a record. The name is what reaches storage and the wire verbatim. */
enum class LogLevel { DEBUG, WARNING, ERROR }

/**
 * The subsystem a record belongs to.
 *
 * An interface rather than an enum, because the closed-set discipline has to belong to each
 * consumer: a library cannot know an application's subsystems, and an application should not be
 * able to invent tags at the call site. Every consumer declares its own enum —
 * `enum class MyTags(override val tag: String) : LogTag` — and keeps the property that made the
 * original closed enum worth having: no near-duplicates (`"colour"` versus `"Colour"`), no
 * placeholders, and every line a subsystem writes filters together.
 *
 * [tag] is what reaches storage, the wire, and platform log output. Keep them PascalCase.
 */
interface LogTag {
    val tag: String
}

/** Where a record was made from, when the platform can tell. */
data class CallSite(
    val className: String?,
    val methodName: String?,
    val fileName: String?,
    val line: Int?,
) {
    companion object {
        val UNKNOWN = CallSite(null, null, null, null)
    }
}

/** One record, as it leaves the call site. Storage and wire formats are built from this. */
data class LogEvent(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val filterTag: String?,
    val callSite: CallSite,
    val throwable: Throwable?,
    val data: String?,
    val timestampEpochMillis: Long,
)

/**
 * Where records go. Implemented by the application — platform log output, a database, a stream, a
 * test double — and installed on [Diagnostics] at startup.
 *
 * [emit] is called on the calling thread and must not block: persist asynchronously, and drop
 * rather than wait if a queue is full. Never log from inside an implementation; that recurses.
 */
interface DiagnosticSink {
    fun emit(event: LogEvent)
}

/**
 * The logging entry point: **inert until a sink is installed**, and lazy in its message.
 *
 * ### Why the message is a lambda
 * Kotlin evaluates arguments before the call, so `debug(tag, "derived key for $member")` builds
 * that string *before* any gate can reject it — materialising whatever it interpolated into an
 * immutable String that lives until garbage collection, in a build that logs nothing. For a
 * library whose secrets are deliberately zeroed on every exit path, that is a leak with no
 * upside. Taking `() -> String` means the string is never constructed when nothing is listening:
 * guaranteed by construction rather than by trusting an optimiser to prove purity.
 *
 * ### How the release-build guarantee survives
 * The application installs a sink only behind its own build-time constant:
 *
 * ```kotlin
 * if (FeatureFlags.developerMode) Diagnostics.install(AndroidSink(context))
 * ```
 *
 * In a release build that branch is dead code, the shrinker removes the call, no sink is ever
 * reachable, and every log call short-circuits on a null check without invoking its lambda. There
 * is no runtime path to turn logging on afterwards: [install] is the only door, and nothing in a
 * release build calls it.
 *
 * ### The rule that outlives the gate
 * Even with logging on, **key material must not be passed in**. Developer builds persist records
 * for days, stream them in plaintext over a LAN, and export them to files that end up attached to
 * bug reports. A dev-mode leak is not ephemeral.
 */
object Diagnostics {

    // Not @Volatile: that annotation is JVM-only, and this is common code. Installation happens
    // once at startup, long before the writes that read it, so a torn read is not a real hazard —
    // the cost of a missed record in the first microseconds is nil, and the alternative is an
    // expect/actual for a field.
    private var sink: DiagnosticSink? = null

    /** True when something is listening. Call sites do not need this; they are already cheap. */
    val isInstalled: Boolean get() = sink != null

    /**
     * Installs [sink], replacing any previous one. Call it behind a build-time developer flag so
     * that release builds have no reachable path to logging at all.
     */
    fun install(sink: DiagnosticSink) {
        this.sink = sink
    }

    /** Removes the sink, returning to the inert state. Chiefly for tests. */
    fun uninstall() {
        sink = null
    }

    /**
     * Developer chatter: gone entirely when no sink is installed, lambda and all.
     *
     * [filterTag] labels one *run* of something — a single pairing attempt, one sync pass — so its
     * records can be pulled out later. Free text, because a run is not known in advance.
     *
     * [data] is a payload too big for the message and not a stack trace: an object, JSON, a dump.
     * It is a lambda for the same reason the message is, and `toString()` runs on the calling
     * thread so later mutation cannot change what was recorded.
     */
    inline fun debug(
        tag: LogTag,
        filterTag: String? = null,
        throwable: Throwable? = null,
        noinline data: (() -> Any?)? = null,
        message: () -> String,
    ) {
        if (!isInstalled) return
        emit(LogLevel.DEBUG, tag, message(), filterTag, throwable, data?.invoke()?.toString())
    }

    /** A condition worth knowing about in any build that is listening. */
    inline fun warning(
        tag: LogTag,
        filterTag: String? = null,
        throwable: Throwable? = null,
        noinline data: (() -> Any?)? = null,
        message: () -> String,
    ) {
        if (!isInstalled) return
        emit(LogLevel.WARNING, tag, message(), filterTag, throwable, data?.invoke()?.toString())
    }

    /** A failure. Pass [throwable] rather than stringifying a trace into the message. */
    inline fun error(
        tag: LogTag,
        filterTag: String? = null,
        throwable: Throwable? = null,
        noinline data: (() -> Any?)? = null,
        message: () -> String,
    ) {
        if (!isInstalled) return
        emit(LogLevel.ERROR, tag, message(), filterTag, throwable, data?.invoke()?.toString())
    }

    /**
     * Builds and delivers the record. Public only because the inline functions above need it;
     * call those instead, so a message is never built for a sink that does not exist.
     */
    @PublishedApi
    internal fun emit(
        level: LogLevel,
        tag: LogTag,
        message: String,
        filterTag: String?,
        throwable: Throwable?,
        data: String?,
    ) {
        val target = sink ?: return
        target.emit(
            LogEvent(
                level = level,
                tag = tag.tag,
                message = message,
                filterTag = filterTag,
                callSite = captureCallSite(),
                throwable = throwable,
                data = data,
                timestampEpochMillis = epochMillis(),
            )
        )
    }
}

/** The first stack frame outside the logging plumbing, where the platform can walk a stack. */
internal expect fun captureCallSite(): CallSite

/** The platform clock, exposed so sinks in other modules default to the same time source. */
expect fun epochMillis(): Long
