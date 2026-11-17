plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI passes the module version; local builds stay clearly marked as development builds.
val appVersionCode = (findProperty("appVersionCode") as String?)?.toInt() ?: 1
val appVersionName = (findProperty("appVersionName") as String?) ?: "dev"
// Release signing is configured only when CI provides the keystore through the environment.
val keystorePath: String? = System.getenv("SHELLSCALED_KEYSTORE")

android {
    namespace = "io.github.aayydev.shellscaled"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.aayydev.shellscaled"
        minSdk = 23
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
    }

    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("SHELLSCALED_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SHELLSCALED_KEY_ALIAS")
                keyPassword = System.getenv("SHELLSCALED_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        generateLocaleConfig = true
        // Ship only the three supported languages, so library UI never mixes in others.
        localeFilters += listOf("en", "zh", "zh-rCN", "zh-rTW", "zh-rHK", "b+zh+Hant")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // strings_webui.xml is generated verbatim from upstream's WebUI copy; versions are pinned on purpose.
        disable += setOf("Typos", "TypographyDashes", "NewerVersionAvailable", "GradleDependency", "AndroidGradlePluginVersion", "VectorRaster")
        abortOnError = true
        checkDependencies = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.github.topjohnwu.libsu:core:5.2.2")
    implementation("com.google.zxing:core:3.5.4")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(composeBom)
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
