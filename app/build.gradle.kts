plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.compressorai"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.example.compressorai"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }

    sourceSets {
        getByName("main").assets.srcDir("$buildDir/generated/modelAssets")
    }
}

val modelBundleRoot = providers
    .gradleProperty("modelBundleDir")
    .orElse("/Users/tyejaedon/PycharmProjects/AI_Compressor/models/production_bundle/best_20260702_235656")

val imageModelFileName = providers.gradleProperty("imageModelFile")
val audioModelFileName = providers.gradleProperty("audioModelFile")
val videoModelFileName = providers.gradleProperty("videoModelFile")

val syncModelAssets by tasks.registering(Copy::class) {
    val imageModelDir = modelBundleRoot.map { file("$it/image") }
    val audioModelDir = modelBundleRoot.map { file("$it/audio") }
    val videoModelDir = modelBundleRoot.map { file("$it/video") }
    var resolvedImageModelFile: File? = null
    var resolvedAudioModelFile: File? = null
    var resolvedVideoModelFile: File? = null
    into(layout.buildDirectory.dir("generated/modelAssets/models"))

    doFirst {
        fun resolveModelFile(dir: File, expectedAssetName: String, configuredName: String?): File {
            val configured = configuredName?.takeIf { it.isNotBlank() }?.let { dir.resolve(it) }
            if (configured != null && configured.exists()) return configured
            val expected = dir.resolve(expectedAssetName)
            if (expected.exists()) return expected
            val availableTflite = dir.listFiles()?.filter { it.isFile && it.extension == "tflite" }.orEmpty()
            if (availableTflite.size == 1) return availableTflite.first()
            val availableNames = dir.listFiles()?.map { it.name }?.sorted().orEmpty().joinToString()
            error(
                "Missing model for ${dir.absolutePath}. Expected '$expectedAssetName'" +
                    (configuredName?.let { " or configured '$it'" } ?: "") +
                    ". Found tflite=${availableTflite.map { it.name }}. Files=[$availableNames]."
            )
        }

        val requiredFiles = listOf(
            resolveModelFile(imageModelDir.get(), "production_model.tflite", imageModelFileName.orNull).also { resolvedImageModelFile = it },
            resolveModelFile(audioModelDir.get(), "audio_autoencoder.tflite", audioModelFileName.orNull).also { resolvedAudioModelFile = it },
            resolveModelFile(videoModelDir.get(), "video_autoencoder.tflite", videoModelFileName.orNull).also { resolvedVideoModelFile = it },
        )
        requiredFiles.forEach { modelFile ->
            check(modelFile.exists()) {
                "Missing required model asset: ${modelFile.absolutePath}. Set -PmodelBundleDir=<bundle_root> if needed."
            }
        }
    }

    from({ resolvedImageModelFile }) {
        rename { "production_model.tflite" }
        eachFile {
            path = "image/$path"
        }
    }
    from(imageModelDir) {
        include("*.json", "*.txt", "*.md", "*.csv")
        eachFile {
            path = "image/$path"
        }
    }
    from({ resolvedAudioModelFile }) {
        rename { "audio_autoencoder.tflite" }
        eachFile {
            path = "audio/$path"
        }
    }
    from(audioModelDir) {
        include("*.json", "*.txt", "*.md", "*.csv")
        eachFile {
            path = "audio/$path"
        }
    }
    from({ resolvedVideoModelFile }) {
        rename { "video_autoencoder.tflite" }
        eachFile {
            path = "video/$path"
        }
    }
    from(videoModelDir) {
        include("*.json", "*.txt", "*.md", "*.csv")
        eachFile {
            path = "video/$path"
        }
    }
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
}

tasks.named("preBuild") {
    dependsOn(syncModelAssets)
}

dependencies {
    implementation(project(":core-ui"))
    implementation(project(":core-domain"))
    implementation(project(":core-data"))
    implementation(project(":core-ml"))
    implementation(project(":feature-image"))
    implementation(project(":feature-audio"))
    implementation(project(":feature-video"))
    implementation(project(":benchmark"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)


    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}