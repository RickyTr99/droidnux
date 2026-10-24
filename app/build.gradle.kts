import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing: set "signing.properties" in local.properties to the path of a file with
// storeFile, storePassword, keyAlias and keyPassword. Without it, release builds are unsigned.
// Debug builds use the standard debug key (~/.android/debug.keystore); CI restores the same one.
val signingProps = Properties().apply {
    val local = rootProject.file("local.properties")
    if (local.exists()) {
        val path = Properties().apply { local.inputStream().use { load(it) } }.getProperty("signing.properties")
        if (path != null && file(path).exists()) file(path).inputStream().use { load(it) }
    }
}

android {
    namespace = "app.droidnux"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "app.droidnux"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1-dev"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        if (!signingProps.isEmpty) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            if (!signingProps.isEmpty) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            // Burrow and the guest helpers are executables shipped as lib*.so:
            // they must be extracted to disk to be runnable.
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":container"))
    implementation(project(":display"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
