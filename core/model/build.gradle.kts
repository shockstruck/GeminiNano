plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    testImplementation(libs.junit)
}

// The repository-wide verification command uses testDebugUnitTest; this module is a
// pure JVM library, so expose the same task name for the aggregate root task.
tasks.register("testDebugUnitTest") {
    group = "verification"
    description = "Runs the JVM unit tests of :core:model."
    dependsOn(tasks.named("test"))
}
