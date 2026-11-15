plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystoreFile: String? = System.getenv("KEYSTORE_FILE")?.takeIf { it.isNotEmpty() }
fun envOr(name: String, default: String) = System.getenv(name)?.takeIf { it.isNotEmpty() } ?: default
// CI sets VERSION_CODE (100 + run number) so every build is newer than the last.
val buildVersion = envOr("VERSION_CODE", "3").toInt()

android {
    namespace = "com.cloudplay.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cloudplay.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = buildVersion
        versionName = "1.$buildVersion"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "UPDATE_REPO", "\"${envOr("GITHUB_REPOSITORY", "nawafz2011-blip/onedraive")}\"")
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = envOr("KEYSTORE_PASSWORD", "cloudplay")
                keyAlias = envOr("KEY_ALIAS", "cloudplay")
                keyPassword = envOr("KEY_PASSWORD", "cloudplay")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keystoreFile != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
