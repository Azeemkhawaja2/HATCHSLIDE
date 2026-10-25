plugins {
    alias(libs.plugins.hatchslide.android.library)
}

android {
    namespace = "com.hatchslide.core.audio"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
