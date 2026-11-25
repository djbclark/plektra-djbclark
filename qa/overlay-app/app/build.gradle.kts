import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.hitlbar.overlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.hitlbar.overlay"
        minSdk = 30
        targetSdk = 36
        // The host reads it: from 2 the app shows the wait card itself on a tap, from 3 it has the test bench.
        versionCode = 5
        versionName = "0.4.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").java.srcDir("src/main/kotlin")
        getByName("test").java.srcDir("src/test/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Unit tests only; the app itself has no dependencies beyond the platform.
    testImplementation("junit:junit:4.13.2")
}
