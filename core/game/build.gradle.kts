plugins {
    alias(libs.plugins.hatchslide.jvm.library)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.test {
    // Level validation reads the committed level files that ship in the app's assets.
    systemProperty("hatchslide.levels", rootProject.file("app/src/main/assets/levels").absolutePath)
    maxHeapSize = "3g"
}
