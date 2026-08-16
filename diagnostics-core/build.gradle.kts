plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
}

group = "dev.diagnostics"
version = "0.1.0"

kotlin {
    jvm()
    androidTarget()
    listOf(iosArm64(), iosSimulatorArm64())
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
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}
