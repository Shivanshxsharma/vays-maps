import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure Kotlin, no Android and no third-party libraries: the same engine runs on the
// phone and in JVM tests / the command-line tools on a PC.
plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.10")
}
