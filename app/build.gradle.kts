import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional local overrides for the Piper native build - see docs/NATIVE_BUILD.md.
// Put these two lines in local.properties (NOT committed) once you have built them:
//   onnxruntime.aar.dir=/absolute/path/to/extracted/onnxruntime-android-1.22.0
//   espeakng.ndk.dir=/absolute/path/to/espeak-ng-android-install
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val onnxruntimeAarDir: String? = localProps.getProperty("onnxruntime.aar.dir")
val espeakNgNdkDir: String? = localProps.getProperty("espeakng.ndk.dir")

android {
    namespace = "com.isro.itantra"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.isro.itantra"
        minSdk = 24        // covers low/mid-range devices per PS 26173 hardware constraint
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
                if (onnxruntimeAarDir != null) arguments += "-DONNXRUNTIME_AAR_DIR=$onnxruntimeAarDir"
                if (espeakNgNdkDir != null) arguments += "-DESPEAK_NG_NDK_DIR=$espeakNgNdkDir"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
