plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.shockstruck.gemininano.core.genai"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        // ML Kit GenAI requires API 26; the app keeps minSdk 24 and guards at runtime.
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
}
