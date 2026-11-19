plugins {
    id("com.android.application") version "9.4.1" apply false
    // Must match the Kotlin version AGP 9.4.1 ships with (built-in Kotlin).
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
}
