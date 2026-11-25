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
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.mlkit.genai.rewriting)
    implementation(libs.mlkit.genai.proofreading)
    implementation(libs.mlkit.genai.image.description)
    implementation(libs.mlkit.genai.embedding)
    implementation(libs.mlkit.genai.agent)
    implementation(libs.mlkit.genai.speech.recognition)
    implementation(libs.mlkit.genai.speech.translation)
    implementation(libs.mlkit.genai.text.to.speech)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.guava)
}
