plugins {
    alias(libs.plugins.hatchslide.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hatchslide.core.data"
}

dependencies {
    api(project(":core:game"))
    api(libs.androidx.datastore)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
