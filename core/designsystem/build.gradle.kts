plugins {
    alias(libs.plugins.hatchslide.android.library)
    alias(libs.plugins.hatchslide.android.compose)
}

android {
    namespace = "com.hatchslide.core.designsystem"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
