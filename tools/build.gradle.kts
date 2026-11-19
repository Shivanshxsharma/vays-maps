import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// PC-side helper: build a route pack from live data, print its spoken guidance,
// render a preview PNG and replay a simulated drive.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies { implementation(project(":core")) }

application { mainClass.set("com.shivansh.raasta.tools.CliKt") }
tasks.named<JavaExec>("run") { workingDir = rootProject.projectDir }
