import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing lives outside the repository: ~/.config/cleanframe-tv/keystore.properties
val signing = Properties().apply {
    val file = File(System.getProperty("user.home"), ".config/cleanframe-tv/keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "io.github.mdrzn.cleanframetv"
    compileSdk = 36
    buildToolsVersion = "36.1.0"

    buildFeatures { buildConfig = true }

    defaultConfig {
        applicationId = "io.github.mdrzn.cleanframetv"
        minSdk = 26
        targetSdk = 36
        versionCode = 4
        versionName = "0.3.1"
        // Updates are off unless a build is given the public half of the update-signing key (gradle -PupdatePublicKey=<base64 X.509 DER>).
        buildConfigField("String", "UPDATE_PUBLIC_KEY", "\"${providers.gradleProperty("updatePublicKey").getOrElse("")}\"")
        buildConfigField("String", "UPDATE_BASE_URL", "\"${providers.gradleProperty("updateBaseUrl").getOrElse("https://raw.githubusercontent.com/masterde/cleanframe-tv/updates/")}\"")
    }

    signingConfigs {
        create("release") {
            if (!signing.isEmpty) {
                storeFile = File(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (signing.isEmpty) signingConfigs.getByName("debug") else signingConfigs.getByName("release")
        }
    }

    // The web interface in ../../app is bundled as-is and served by WebViewAssetLoader.
    sourceSets["main"].assets.srcDirs("../../app")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
}
