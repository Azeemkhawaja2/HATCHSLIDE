plugins {
    alias(libs.plugins.hatchslide.android.library)
    alias(libs.plugins.hatchslide.android.compose)
}

android {
    namespace = "com.hatchslide.feature.game"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:render"))
    implementation(project(":core:audio"))
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.compose.material3.adaptive)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
