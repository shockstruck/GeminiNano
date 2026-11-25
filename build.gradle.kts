// Top-level build file. The real configuration lives in the app and core modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Aggregate tasks so the documented verification command works from the repository root:
//   ./gradlew --no-daemon assembleOfflineDebug testDebugUnitTest lintDebug
// The app has product flavors, so its own task names are testOfflineDebugUnitTest and
// lintOfflineDebug; the pure JVM modules expose testDebugUnitTest aliases.
val aggregateTasks = mapOf(
    "assembleOfflineDebug" to listOf("assembleOfflineDebug"),
    "testDebugUnitTest" to listOf("testDebugUnitTest", "testOfflineDebugUnitTest"),
    "lintDebug" to listOf("lintDebug", "lintOfflineDebug"),
)

aggregateTasks.forEach { (taskName, _) ->
    tasks.register(taskName) {
        group = "verification"
        description = "Runs $taskName in every module that provides it."
    }
}

gradle.projectsEvaluated {
    aggregateTasks.forEach { (taskName, candidates) ->
        tasks.named(taskName) {
            dependsOn(
                subprojects.flatMap { subproject ->
                    subproject.tasks.matching { it.name in candidates }.toList()
                },
            )
        }
    }
}
