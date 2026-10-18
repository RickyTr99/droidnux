plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.droidnux.container"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=none")
                // CI sets DROIDNUX_CCACHE=ccache to cache native builds.
                System.getenv("DROIDNUX_CCACHE")?.let {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=$it", "-DCMAKE_CXX_COMPILER_LAUNCHER=$it")
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.commons.compress)
    implementation(libs.xz)
}
