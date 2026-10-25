plugins {
    alias(libs.plugins.hatchslide.android.library)
}

android {
    namespace = "com.hatchslide.core.render"
}

dependencies {
    api(project(":core:game"))
    api(libs.filament.android)
    implementation(libs.filament.gltfio.android)
    implementation(libs.filament.utils.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
