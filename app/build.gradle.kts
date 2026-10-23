// Project license: GPL-3.0-only. See /LICENSE and THIRD_PARTY_NOTICES.md.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.wddclass.clicktocast"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.wddclass.clicktocast"
        minSdk = 23
        targetSdk = 35
        versionCode = 11
        versionName = "0.1.0"
        vectorDrawables.useSupportLibrary = true
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Keep core library desugaring off. D8's language desugar does not rewrite
        // java.io methods, and desugar_jdk_libs does not backport
        // ByteArrayOutputStream.toString(Charset) (API 33). Higher java.* calls
        // have to be avoided; NewApi below fails the build when one slips in.
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("signing/clicktocast-debug.keystore")
            storePassword = "android"
            keyAlias = "clicktocast"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/NOTICE.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Fatal so lintVitalRelease (part of assembleRelease) rejects minSdk violations,
        // including java.* methods that exist on the JDK but not on API 23.
        fatal += setOf("NewApi")
        disable += setOf("InsecureBaseConfiguration", "UnusedAttribute")
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

base {
    archivesName.set("ClickToCast-${android.defaultConfig.versionName}")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    val media3 = "1.8.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.78.1")

    testImplementation("junit:junit:4.13.2")
}
