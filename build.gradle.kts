buildscript {
    repositories { google() }
    dependencies {
        // Newer R8 than AGP 8.13 bundles: it understands Kotlin 2.4 metadata (needed by the
        // offline AI libraries).
        classpath("com.android.tools:r8:9.4.34")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
