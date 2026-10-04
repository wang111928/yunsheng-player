import com.android.build.OutputFile
import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val nmlVersionCode = 228
val nmlVersionName = "0.2.28"
// Explicitly opt in to the isolated authorization experiment. Stable installs keep their identity.
val authHandoffExperiment = providers.gradleProperty("nmlAuthHandoffExperiment")
    .map { it.toBooleanStrict() }.getOrElse(false)
if (authHandoffExperiment && gradle.startParameter.taskNames.any {
        val task = it.substringAfterLast(':')
        !task.contains("FullDebug", ignoreCase = true) && task != "generateFullReleaseBuildConfig"
    }) {
    error("The authorization experiment requires explicit FullDebug tasks; Min and aggregate builds are disabled.")
}

android {
    namespace = "com.litemusic.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.litemusic.app"
        minSdk = 28
        targetSdk = 36
        // Keep the debug application's identity and signing key stable so Android can update it
        // in place.  The version code must always increase for an installed fullDebug build.
        versionCode = nmlVersionCode
        versionName = nmlVersionName
        vectorDrawables { useSupportLibrary = true }
        buildConfigField("boolean", "AUTH_HANDOFF_EXPERIMENT", "false")
        manifestPlaceholders["authHandoffEnabled"] = "false"
    }

    flavorDimensions += "edition"
    productFlavors {
        create("min") {
            dimension = "edition"
            applicationIdSuffix = ".min"
            buildConfigField("boolean", "FEATURE_SOCIAL", "false")
            buildConfigField("boolean", "FEATURE_COMMENT", "false")
            buildConfigField("boolean", "FEATURE_TOGETHER", "false")
            buildConfigField("boolean", "FEATURE_MSG", "false")
            buildConfigField("String", "FLAVOR_NAME", "\"min\"")
        }
        create("full") {
            dimension = "edition"
            applicationIdSuffix = ".full"
            buildConfigField("boolean", "FEATURE_SOCIAL", "true")
            buildConfigField("boolean", "FEATURE_COMMENT", "true")
            buildConfigField("boolean", "FEATURE_TOGETHER", "true")
            buildConfigField("boolean", "FEATURE_MSG", "true")
            buildConfigField("String", "FLAVOR_NAME", "\"full\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            buildConfigField("boolean", "AUTH_HANDOFF_EXPERIMENT", authHandoffExperiment.toString())
            manifestPlaceholders["authHandoffEnabled"] = authHandoffExperiment.toString()
            applicationIdSuffix = if (authHandoffExperiment) ".authprobe" else ".debug"
            if (authHandoffExperiment) {
                versionNameSuffix = "-authprobe"
                resValue("string", "app_name", "网易云轻量版 · 登录实验")
            }
            // Test APKs are distributed to devices; keep the debuggable package small too.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        // Compress the bundled OCR library in the distributed APK. Android extracts it once
        // on installation; the engine still runs fully offline.
        jniLibs.useLegacyPackaging = true
        // Compress code in the download too; Android manages loading on supported devices.
        dex.useLegacyPackaging = true
    }
    // Device builds target arm64 only; the user no longer needs emulator APKs.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a")
            isUniversalApk = false
        }
    }
}

// A versioned filename prevents a phone file manager from opening a stale same-named APK.
// This changes only the exported artifact name; it does not change the application id or signing.
android.applicationVariants.all {
    val isAuthProbe = authHandoffExperiment && buildType.name == "debug"
    outputs.all {
        val abi = (this as BaseVariantOutputImpl).getFilter(OutputFile.ABI) ?: "universal"
        val experimentSuffix = if (isAuthProbe) "-authprobe" else ""
        outputFileName = "NeteaseMusicLite-$name-v$nmlVersionName-vc$nmlVersionCode-$abi$experimentSuffix.apk"
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":shared"))
    implementation(project(":network-core"))
    implementation(project(":data-core"))
    implementation(project(":lyric-engine"))
    implementation(project(":player-core"))
    implementation(project(":design-system"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.koin.core)
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)
    // Bundled Chinese/Latin recognition works offline without Google Play services.
    // The arm64 artifact is size-checked against the user's 35 MB limit.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")

    // 扫码登录：本地生成二维码（vendored 纯 Java 库，离线构建可用）
    implementation(files("libs/zxing-core-3.5.3.jar"))

}
