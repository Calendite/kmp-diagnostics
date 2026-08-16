package dev.diagnostics

import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * Kotlin/Native can produce a stack trace, but symbol names come back mangled and are absent
 * altogether in release binaries. Reporting nothing is more honest than reporting noise; the
 * record still carries its tag, message and timestamp.
 */
internal actual fun captureCallSite(): CallSite = CallSite.UNKNOWN

internal actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()
