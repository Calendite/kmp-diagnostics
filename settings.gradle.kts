rootProject.name = "kmp-diagnostics"

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Two modules on purpose: `:diagnostics-core` has **zero dependencies**, so a library with an
// auditable dependency surface (layered-encryption-protocol) can emit through it without
// inheriting anything; `:diagnostics-runtime` carries the queue (coroutines) and the wire
// format (serialization) for applications.
include(":diagnostics-core")
include(":diagnostics-runtime")
