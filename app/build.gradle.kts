import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing comes from the environment (CI decodes a GitHub secret into a file, see
// docs/RELEASING.md). Without it, release builds are signed with the local debug key.
val releaseKeystore = System.getenv("SK_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }

android {
    namespace = "com.itzsuli.smartkeyboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.itzsuli.smartkeyboard"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "1.3.1"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("SK_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SK_KEY_ALIAS")
                keyPassword = System.getenv("SK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
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

    androidResources {
        // Dictionaries are already gzip-compressed.
        noCompress += listOf("dict", "gz")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/{AL2.0,LGPL2.1}", "DebugProbesKt.bin", "META-INF/versions/9/module-info.class",
            "META-INF/native-image/**", "META-INF/licenses/**", "META-INF/LICENSE*", "META-INF/NOTICE*",
            "META-INF/DEPENDENCIES", "META-INF/INDEX.LIST",
        )
        // The offline AI runtime is large: compressed, the APK download is about half the size.
        jniLibs.useLegacyPackaging = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Claude provider for the AI features (official SDK); align kotlin-reflect with our Kotlin.
    implementation(libs.anthropic.java)
    implementation(libs.kotlin.reflect)
    // Offline AI: a local model (LiteRT-LM) on any recent phone, Gemini Nano where Android has it.
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.genai.prompt)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
