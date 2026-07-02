plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.core.ml"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    api(project(":core-domain"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.tensorflow.lite)
    implementation(libs.tensorflow.lite.gpu)
    testImplementation(libs.junit)
}

