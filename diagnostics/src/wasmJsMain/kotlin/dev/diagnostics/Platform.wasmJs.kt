package dev.diagnostics

/** The browser has no usable Kotlin stack frames; the record carries everything else. */
internal actual fun captureCallSite(): CallSite = CallSite.UNKNOWN

private fun jsNow(): Double = js("Date.now()")

internal actual fun epochMillis(): Long = jsNow().toLong()
