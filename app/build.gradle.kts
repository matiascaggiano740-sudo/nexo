import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseVersion = Properties().apply {
    rootProject.file("version.properties").inputStream().use { load(it) }
}
val updaterApiUrl = providers.gradleProperty("nexo.test.updateApiUrl")
    .orElse("https://api.github.com/repos/matiascaggiano740-sudo/nexo/releases/latest")
    .get()
val allowTestCleartext = providers.gradleProperty("nexo.test.allowCleartext").orElse("false").get()
val allowTestLoopbackHttp = providers.gradleProperty("nexo.test.allowLoopbackHttp").orElse("false").get().toBoolean()
val configuredVersionCode = providers.gradleProperty("nexo.test.versionCode")
    .orElse(releaseVersion.getProperty("VERSION_CODE")).get().toInt()
val signDebugWithReleaseForOtaTest = providers.gradleProperty("nexo.test.signDebugWithRelease")
    .orElse("false").get().toBoolean()

android {
    namespace = "com.nexo.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nexo.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = configuredVersionCode
        versionName = releaseVersion.getProperty("VERSION_NAME")
        manifestPlaceholders["nexoAllowTestCleartext"] = allowTestCleartext
        buildConfigField("String", "NEXO_RELEASES_API_URL", "\"$updaterApiUrl\"")
        buildConfigField("boolean", "NEXO_ALLOW_TEST_LOOPBACK_HTTP", allowTestLoopbackHttp.toString())
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("nexo-release-final.p12")
storeType = "PKCS12"
            storePassword = System.getenv("NEXO_STORE_PASSWORD")
                ?: error("Falta NEXO_STORE_PASSWORD")
            keyAlias = "nexo"
            keyPassword = System.getenv("NEXO_KEY_PASSWORD")
                ?: error("Falta NEXO_KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            if (signDebugWithReleaseForOtaTest) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// A release APK is built only after the real Kotlin home-grid tests pass.
tasks.configureEach {
    if (name == "assembleRelease") {
        dependsOn("testReleaseUnitTest")
        dependsOn("compileDebugAndroidTestKotlin")
    }
}
