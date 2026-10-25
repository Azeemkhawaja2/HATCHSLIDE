plugins {
    alias(libs.plugins.hatchslide.android.library)
    alias(libs.plugins.hatchslide.android.compose)
}

android {
    namespace = "com.hatchslide.feature.menu"
}

dependencies {
    implementation(project(":core:designsystem"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(project(":core:audio"))
    implementation(project(":core:render"))
}
