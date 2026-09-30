// EchoNote — root build script.
// All plugin versions are pinned to versions that resolve from the local Gradle cache
// first (see docs/ARCHITECTURE.md "Build toolchain" for the verified matrix).
plugins {
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
