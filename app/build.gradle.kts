import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
}

android {
    namespace = "ai.wiro.realtime"
    compileSdk = 37

    defaultConfig {
        applicationId = "ai.wiro.realtime"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        val secrets = loadSecrets()
        buildConfigField(
            "String",
            "WIRO_API_KEY",
            quotedBuildConfig(
                System.getenv("WIRO_API_KEY")
                    ?: secrets.getProperty("WIRO_API_KEY")
                    ?: "",
            ),
        )
        buildConfigField(
            "String",
            "WIRO_API_SECRET",
            quotedBuildConfig(
                System.getenv("WIRO_API_SECRET")
                    ?: secrets.getProperty("WIRO_API_SECRET")
                    ?: "",
            ),
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt",
                ),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

private fun loadSecrets(): Properties {
    val properties = Properties()
    val file = rootProject.file("secrets.properties")
    if (file.exists()) {
        file.inputStream().use(properties::load)
    }
    return properties
}

private fun quotedBuildConfig(value: String): String {
    val escaped =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
    return "\"$escaped\""
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        jvmTarget.set(
            org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17,
        )
    }
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom("src/main/java")
    buildUponDefaultConfig = true
    parallel = true
    ignoreFailures = false
    basePath.set(rootProject.projectDir)
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
