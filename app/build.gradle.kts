plugins {
    alias(libs.plugins.agp.app)
}

// 只在本机/CI 提供了 keystore 时才使用正式签名，否则回退到 debug 签名。
val releaseKeystore = System.getenv("KEYSTORE_FILE").orEmpty()

android {
    namespace = "com.darknight.MiuiHomeMod"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.darknight.MiuiHomeMod"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
    }

    signingConfigs {
        if (releaseKeystore.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD") ?: System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles("proguard-rules.pro")
            signingConfig = if (releaseKeystore.isNotEmpty()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
            excludes += "**"
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
}
