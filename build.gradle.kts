plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.android.asset.pack) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.hatchslide.root.gates)
}

// Deterministic asset build (spec section 8). Python skips outputs whose inputs are unchanged.
tasks.register<Exec>("generateAssets") {
    group = "build"
    description = "Generates models, textures, skies, audio and fonts with tools/assetgen/build_all.py."
    workingDir = rootDir
    commandLine(providers.environmentVariable("PYTHON").getOrElse("python3"), "tools/assetgen/build_all.py")
}
