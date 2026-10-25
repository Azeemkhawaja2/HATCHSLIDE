plugins {
    alias(libs.plugins.hatchslide.android.library)
    alias(libs.plugins.hatchslide.android.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hatchslide.feature.game"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:render"))
    implementation(project(":core:audio"))
    implementation(libs.androidx.lifecycle.viewmodel.savedstate)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
