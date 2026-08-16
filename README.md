# kmp-diagnostics

A Kotlin Multiplatform diagnostics library: structured logging that is **inert until something is
listening**, and a wire format for streaming records to a developer's machine.

Extracted from the Calendite app so that libraries — including
[`layered-encryption-protocol`](https://github.com/Calendite/layered-encryption-protocol), which
was previously a black box in the hardest part of the app to debug — can emit diagnostics without
inheriting an application's dependencies or its idea of what a subsystem is.

Targets JVM, Android, iOS and Wasm/JS. Depends only on kotlinx-serialization and coroutines.

## The two properties that shape the API

**Nothing is evaluated unless a sink is installed.** Kotlin evaluates arguments before the call,
so a conventional `debug("derived key for $member")` builds that string *before* any gate can
reject it — materialising whatever it interpolated into an immutable, non-zeroable `String` in a
build that logs nothing. Every entry point here takes its message (and its payload) as a lambda,
so nothing is built when nothing is listening. Guaranteed by construction, not by hoping a
shrinker can prove purity.

```kotlin
Diagnostics.debug(MyTags.SYNC) { "ingested ${envelopes.size} envelopes" }
```

**Release builds have no reachable path to logging.** The library ships with no sink. The
application installs one behind its own build-time constant:

```kotlin
if (FeatureFlags.developerMode) Diagnostics.install(CompositeSink(LogcatSink(), queueingSink))
```

In a release build that branch is dead code, the shrinker removes the install call, no sink is
ever reachable, and every call site short-circuits on a null check without invoking its lambda.
`install` is the only door, and nothing in a release build calls it.

## Tags belong to the consumer

`LogTag` is an interface, not an enum, because a library cannot know an application's subsystems —
but each consumer keeps the closed-set discipline that stops tags drifting into near-duplicates:

```kotlin
enum class MyTags(override val tag: String) : LogTag {
    SYNC("Sync"),
    PAIRING("Pairing"),
}
```

## What is here, and what is not

| In this library | Left to the application |
| --- | --- |
| `Diagnostics` entry points, `LogTag`, `LogLevel`, `LogEvent` | The tag enum itself |
| `DiagnosticSink`, `CompositeSink`, `QueueingSink` | Platform log output; the database behind `LogStore` |
| `LogRecord`, `LogQuery`, call-site capture | Any viewer UI |
| `LogStreamProtocol` — the NDJSON wire format | The TCP server, mDNS advertisement, device codes |

The split is deliberate: storage schemas, sockets and UI are application concerns with heavy
dependencies, and a library that a cryptographic protocol depends on should carry none of them.

## The wire format

NDJSON — one JSON object per line, UTF-8, `\n`-terminated — consumed by the
[Calendite Logging service](https://github.com/Calendite/Calendite-Logging), a Python/Flask app
that discovers phones over mDNS and renders live Logs, Data Map and Sharing Trace views.

Per connection: `hello`, `schema`, replayed `record` history, `live`, then `record`/`db`/`trace`
lines until either side closes. Consumers skip unknown `type`s, so the schema grows additively.
`LogStreamProtocol` is the single source of truth for encoding and is deliberately kept away from
any socket so it stays unit-testable; its tests read as the consumer does, asserting on field
names and null-versus-absent rules because those *are* the contract.

## The rule that outlives the gate

Even with logging on, **key material must not be passed in**. Developer builds persist records for
days, stream them in plaintext over a LAN, and export them to files that end up attached to bug
reports — a dev-mode leak is not ephemeral. The lambda gating protects builds that are not
listening; this rule protects the ones that are.
