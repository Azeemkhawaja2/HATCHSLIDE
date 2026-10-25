import java.util.Properties

plugins {
    alias(libs.plugins.hatchslide.android.application)
    alias(libs.plugins.hatchslide.android.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.baselineprofile)
}

// Upload key comes from keystore.properties (never committed) or environment variables.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun signingValue(key: String, env: String): String? = keystoreProperties.getProperty(key) ?: System.getenv(env)

android {
    namespace = "com.hatchslide.app"

    defaultConfig {
        applicationId = "com.hatchslide.game"
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        val storePath = signingValue("storeFile", "HATCHSLIDE_UPLOAD_STORE_FILE")
        if (storePath != null) {
            create("upload") {
                storeFile = rootProject.file(storePath)
                storePassword = signingValue("storePassword", "HATCHSLIDE_UPLOAD_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "HATCHSLIDE_UPLOAD_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "HATCHSLIDE_UPLOAD_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    packaging {
        jniLibs {
            // Store native libraries uncompressed and page-aligned (16 KB page-size support).
            useLegacyPackaging = false
        }
    }

    androidResources {
        noCompress += listOf("ktx", "glb")
    }

    bundle {
        abi { enableSplit = true }
        density { enableSplit = true }
        texture {
            enableSplit = true
            defaultFormat = "etc2"
        }
    }

    assetPacks += setOf(":hd_assets")

    sourceSets {
        getByName("main") {
            // Generated base-module assets (models, effects, ambience); written by :generateAssets.
            assets.srcDir("src/main/assets-gen")
        }
        getByName("debug") {
            // Debug APKs install without Play asset delivery, so they carry the HD pack themselves.
            assets.srcDir(rootProject.file("hd_assets/src/main/assets"))
        }
    }
}

tasks.configureEach {
    val consumesAssets = (name.startsWith("merge") && name.endsWith("Assets")) || name.startsWith("assetPack")
    if (consumesAssets) dependsOn(":generateAssets")
}

dependencies {
    implementation(project(":core:game"))
    implementation(project(":core:data"))
    implementation(project(":core:render"))
    implementation(project(":core:audio"))
    implementation(project(":core:designsystem"))
    implementation(project(":feature:menu"))
    implementation(project(":feature:map"))
    implementation(project(":feature:game"))
    implementation(project(":feature:journal"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    baselineProfile(project(":baselineprofile"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
