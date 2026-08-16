package dev.diagnostics

/** Sends every record to several sinks — platform log output and storage, say. */
class CompositeSink(private vararg val sinks: DiagnosticSink) : DiagnosticSink {
    override fun emit(event: LogEvent) {
        for (sink in sinks) sink.emit(event)
    }
}
