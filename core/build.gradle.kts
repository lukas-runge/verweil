plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "de.lukasrunge.verweil.core"
        compileSdk = 37
        minSdk = 29
    }

    // The JVM target runs the engine tests and replays recorded days on a desktop.
    jvm()

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.kotlinx.json)
            implementation(libs.sqldelight.coroutines)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.java)
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

sqldelight {
    databases {
        create("VerweilDatabase") {
            packageName = "de.lukasrunge.verweil.core.db"
        }
    }
}

// Replays a raw recording through variants of the track pipeline; see "Tuning by replay" in docs/concept.md.
tasks.register<JavaExec>("replay") {
    group = "verification"
    description = "Replays a recording (JSONL) through track pipeline variants and writes GeoJSON"
    val jvmMain = kotlin.jvm().compilations.getByName("main")
    classpath(jvmMain.output.allOutputs, jvmMain.runtimeDependencyFiles)
    mainClass.set("de.lukasrunge.verweil.core.replay.ReplayToolKt")
    // Paths in --args are relative to the repository root, where the command runs.
    workingDir = rootProject.projectDir
}
