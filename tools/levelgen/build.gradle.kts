plugins {
    alias(libs.plugins.hatchslide.jvm.library)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("com.hatchslide.tools.levelgen.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx6g")
}

dependencies {
    implementation(project(":core:game"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
}

tasks.named<JavaExec>("run") {
    workingDir = rootDir
}
