plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

group = "dev.diagnostics"
version = "0.1.0"

kotlin {
    jvm()
    androidTarget {
        // Inline functions ship as bytecode into every consumer, and Android consumers sit at
        // different javac levels (the encryption library at 11, the app at 17) — so the android
        // target emits the lowest of them, which inlines anywhere.
        compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11) }
    }
    listOf(iosArm64(), iosSimulatorArm64(), iosX64())
    wasmJs { browser() }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Deliberately no commonMain dependencies: this module is what security-sensitive
        // libraries depend on, and its entire value is that it drags nothing in with it.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        // JVM and Android share one implementation: both walk stack traces for call sites.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
    }
}

android {
    namespace = "dev.diagnostics.core"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
