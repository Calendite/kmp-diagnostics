package dev.diagnostics

/**
 * Classes that only pass log calls through. Their frames are skipped so a record points at the
 * code that actually logged, not the wrapper it logged through.
 *
 * Exact class names rather than package prefixes on purpose: a prefix would also swallow real
 * callers that happen to sit in the same package as the plumbing. Consumers that wrap
 * [Diagnostics] in their own helper should register the wrapper with [addLogPlumbing].
 */
private val plumbing = mutableSetOf(
    "dev.diagnostics.Diagnostics",
    "dev.diagnostics.Platform_jvmCommonKt",
)

/** Registers a wrapper class whose frames should not be reported as call sites. */
fun addLogPlumbing(className: String) {
    synchronized(plumbing) { plumbing += className }
}

internal actual fun captureCallSite(): CallSite {
    val skip = synchronized(plumbing) { plumbing.toSet() }
    val frame = Throwable().stackTrace.firstOrNull { frame ->
        // Lambdas and inner classes compile to `Outer$member`, so compare on the outer name.
        frame.className.substringBefore('$') !in skip
    } ?: return CallSite.UNKNOWN

    return CallSite(
        className = frame.className,
        methodName = frame.methodName,
        fileName = frame.fileName,
        line = frame.lineNumber.takeIf { it > 0 },
    )
}

internal actual fun epochMillis(): Long = System.currentTimeMillis()
