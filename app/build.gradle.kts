import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.detekt)
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "voicerecorder-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
        vectorDrawables.useSupportLibrary = true

        // On-device transcription (whisper.cpp). arm64-v8a covers physical
        // devices and Apple-Silicon emulators; x86_64 covers Intel emulators.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // Force optimization for the whisper/ggml native code even in debug
                // builds. Without this, ggml compiles at -O0 and transcription is
                // roughly 10x slower (a 4s clip can take a minute).
                arguments += listOf(
                    "-DCMAKE_C_FLAGS_DEBUG=-O3 -DNDEBUG",
                    "-DCMAKE_CXX_FLAGS_DEBUG=-O3 -DNDEBUG"
                )
            }
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("core")
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs = JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
        // Keep the bundled whisper model (*.bin) uncompressed so it can be
        // copied/mmap'd directly out of the APK.
        noCompress.add("bin")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    ndkVersion = "28.2.13676358"

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

// The whisper.cpp Kotlin wrapper is vendored upstream code; don't lint its style.
tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude("**/com/whispercpp/**")
}

// The transcription + VAD models are too large to commit (and LFS can't push to a fork),
// so they're gitignored and downloaded into assets/ at build time. The APK still
// bundles them exactly as if they were checked in.
fun downloadModel(dest: File, url: String) {
    dest.parentFile.mkdirs()
    logger.lifecycle("Downloading model -> $dest")
    var target = URI(url).toURL()
    repeat(5) {
        val connection = target.openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        if (connection.responseCode in 300..399) {
            target = URI(connection.getHeaderField("Location")).toURL()
            connection.disconnect()
        } else {
            connection.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return
        }
    }
    throw GradleException("Failed to download model from $url")
}

val whisperModelFile = file("src/main/assets/models/ggml-small-q5_1.bin")
val whisperModelUrl =
    "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin"

val vadModelFile = file("src/main/assets/models/ggml-silero-v5.1.2.bin")
val vadModelUrl =
    "https://huggingface.co/ggml-org/whisper-vad/resolve/main/ggml-silero-v5.1.2.bin"

val downloadWhisperModel by tasks.registering {
    description = "Downloads the bundled whisper transcription model if it's missing."
    outputs.file(whisperModelFile)
    onlyIf { !whisperModelFile.exists() }
    doLast { downloadModel(whisperModelFile, whisperModelUrl) }
}

val downloadVadModel by tasks.registering {
    description = "Downloads the bundled Silero VAD model if it's missing."
    outputs.file(vadModelFile)
    onlyIf { !vadModelFile.exists() }
    doLast { downloadModel(vadModelFile, vadModelUrl) }
}

tasks.named("preBuild") {
    dependsOn(downloadWhisperModel, downloadVadModel)
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.eventbus)
    implementation(libs.audiorecordview)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.tandroidlame)
    implementation(libs.autofittextview)

    // On-device transcription
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    detektPlugins(libs.compose.detekt)
}
