import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

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
            if (configured != null) {
                check(configured.exists()) {
                    "Configured model '$configuredName' was not found in ${dir.absolutePath}. " +
                        "Set the correct -P*ModelFile value or update -PmodelBundleDir."
                }
                return configured
            }
            val expected = dir.resolve(expectedAssetName)
            check(expected.exists()) {
                val availableTflite = dir.listFiles()?.filter { it.isFile && it.extension == "tflite" }.orEmpty()
                val availableNames = dir.listFiles()?.map { it.name }?.sorted().orEmpty().joinToString()
                "Missing expected model '$expectedAssetName' in ${dir.absolutePath}. " +
                    "Found tflite=${availableTflite.map { it.name }}. Files=[$availableNames]."
            }
            return expected
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

val verifyArm64SoLoadAlignmentDebug by tasks.registering {
    group = "verification"
    description = "Verifies arm64-v8a .so PT_LOAD segment alignment is >= 16384 in debug APK"
    dependsOn("packageDebug")

    doLast {
        val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
        check(apkFile.exists()) { "Debug APK not found at ${apkFile.absolutePath}" }

        val failures = mutableListOf<String>()
        ZipFile(apkFile).use { zip ->
            zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.startsWith("lib/arm64-v8a/") && it.name.endsWith(".so") }
                .forEach { entry ->
                    val data = zip.getInputStream(entry).use { it.readBytes() }
                    val alignments = elfLoadAlignments(data)
                    if (alignments.isEmpty()) {
                        failures += "${entry.name}: no PT_LOAD segments found"
                    } else {
                        val below16Kb = alignments.filter { it < minPageAlignmentBytes }
                        if (below16Kb.isNotEmpty()) {
                            failures += "${entry.name}: PT_LOAD alignments=$alignments"
                        }
                    }
                }
        }

        check(failures.isEmpty()) {
            "16 KB page-size check failed for arm64 native libs:\n${failures.joinToString("\n")}"
        }
        println("16 KB page-size check passed for arm64-v8a native libs")
    }
}

tasks.configureEach {
    if (name == "assembleDebug") {
        dependsOn(verifyArm64SoLoadAlignmentDebug)
    }
}

tasks.named("preBuild") {
    dependsOn(syncModelAssets)
}

fun elfLoadAlignments(elf: ByteArray): List<Long> {
    if (elf.size < 64 || elf[0] != 0x7f.toByte() || elf[1] != 'E'.code.toByte() || elf[2] != 'L'.code.toByte() || elf[3] != 'F'.code.toByte()) {
        return emptyList()
    }
    val elfClass = elf[4].toInt() and 0xFF
    val dataEncoding = elf[5].toInt() and 0xFF
    if (dataEncoding != 1) return emptyList() // little-endian only

    val buffer = ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN)
    val segments = mutableListOf<Long>()
    if (elfClass == 2) {
        val phoff = buffer.getLong(32).toInt()
        val phentsize = buffer.getShort(54).toInt() and 0xFFFF
        val phnum = buffer.getShort(56).toInt() and 0xFFFF
        repeat(phnum) { index ->
            val base = phoff + (index * phentsize)
            if (base + 56 > elf.size) return@repeat
            val pType = buffer.getInt(base)
            if (pType == 1) {
                segments += buffer.getLong(base + 48)
            }
        }
    } else if (elfClass == 1) {
        val phoff = buffer.getInt(28)
        val phentsize = buffer.getShort(42).toInt() and 0xFFFF
        val phnum = buffer.getShort(44).toInt() and 0xFFFF
        repeat(phnum) { index ->
            val base = phoff + (index * phentsize)
            if (base + 32 > elf.size) return@repeat
            val pType = buffer.getInt(base)
            if (pType == 1) {
                segments += (buffer.getInt(base + 28).toLong() and 0xFFFF_FFFFL)
            }
        }
    }
    return segments
}

val minPageAlignmentBytes = 16_384L

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