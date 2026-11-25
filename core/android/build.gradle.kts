plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.shockstruck.gemininano.core.android"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core:model"))
    implementation(libs.androidx.core.ktx)
}
