import com.hatchslide.buildlogic.CompileMaterialsTask

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

val compileMaterials = tasks.register<CompileMaterialsTask>("compileMaterials") {
    group = "build"
    description = "Compiles materials/*.mat with Filament's matc."
    sources.from(rootProject.fileTree("materials") { include("*.mat") })
    fetchScript.set(rootProject.file("tools/filament/fetch_tools.py"))
    filamentVersion.set(libs.versions.filament)
    python.set(providers.environmentVariable("PYTHON").orElse("python3"))
    outputDir.set(layout.buildDirectory.dir("generated/filamat"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(compileMaterials, CompileMaterialsTask::outputDir)
    }
}
