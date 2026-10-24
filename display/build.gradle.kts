plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.droidnux.display"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_static")
                // CI sets DROIDNUX_CCACHE=ccache to cache native builds.
                System.getenv("DROIDNUX_CCACHE")?.let {
                    arguments += listOf("-DCMAKE_C_COMPILER_LAUNCHER=$it", "-DCMAKE_CXX_COMPILER_LAUNCHER=$it")
                }
                cppFlags += listOf("-std=c++20")
                // Only our libraries, not libwayland's own targets.
                targets += listOf("droidnux-display", "droidnux-guest-kwin", "droidnux-guest-pipewire", "droidnux-guest-im")
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
