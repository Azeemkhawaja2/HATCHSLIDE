package com.hatchslide.buildlogic

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register

class AndroidApplicationConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        extensions.configure<ApplicationExtension> {
            configureAndroidCommon(this)
            defaultConfig.targetSdk = Sdk.TARGET
        }
        val components = extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
        components.onVariants(components.selector().withBuildType("release")) { variant ->
            val name = variant.name.replaceFirstChar { it.uppercase() }
            val gate = tasks.register<VerifyManifestTask>("verify${name}Manifest") {
                group = "verification"
                description = "Checks the merged ${variant.name} manifest for permissions, SDK levels and appCategory."
                mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                expectedTargetSdk.set(Sdk.TARGET)
                expectedMinSdk.set(Sdk.MIN)
                report.set(layout.buildDirectory.file("reports/gates/${variant.name}-manifest.txt"))
            }
            tasks.named("check") { dependsOn(gate) }
            registerReleaseGates(variant.name, variant.artifacts.get(SingleArtifact.BUNDLE), components)
        }
    }

    /** Size report and 16 KB alignment gates on the release bundle (spec sections 8 and 10). */
    private fun Project.registerReleaseGates(
        variantName: String,
        bundle: org.gradle.api.provider.Provider<org.gradle.api.file.RegularFile>,
        components: ApplicationAndroidComponentsExtension,
    ) {
        val bundletool = configurations.maybeCreate("bundletool").apply {
            isCanBeConsumed = false
            isCanBeResolved = true
        }
        dependencies.add("bundletool", libs.findLibrary("bundletool").get())
        val android = extensions.getByType(ApplicationExtension::class.java)
        val buildTools = components.sdkComponents.sdkDirectory.map { it.dir("build-tools/${android.buildToolsVersion}") }
        val apkSet = tasks.register<ApkSetTask>("build${variantName.replaceFirstChar { it.uppercase() }}ApkSet") {
            description = "Builds every split APK of the $variantName bundle with bundletool."
            this.bundle.set(bundle)
            this.bundletool.from(bundletool)
            aapt2.set(buildTools.map { it.file("aapt2") })
            apks.set(layout.buildDirectory.file("outputs/apkset/$variantName.apks"))
        }
        val size = tasks.register<SizeReportTask>("sizeReport") {
            group = "verification"
            description = "Measures the per-device Play download with bundletool and writes SIZE_REPORT.md."
            apks.set(apkSet.flatMap { it.apks })
            this.bundletool.from(bundletool)
            deviceSpecs.from(rootProject.fileTree("tools/verify/devices") { include("*.json") })
            assetManifest.set(rootProject.layout.projectDirectory.file("assets_manifest.json"))
            minMegabytes.set(100)
            maxMegabytes.set(190)
            report.set(rootProject.layout.projectDirectory.file("SIZE_REPORT.md"))
        }
        val align = tasks.register<Verify16KbAlignmentTask>("verify16KbAlignment") {
            group = "verification"
            description = "Checks 16 KB ELF and zip alignment of every 64-bit native library."
            this.bundle.set(bundle)
            apks.set(apkSet.flatMap { it.apks })
            zipalign.set(buildTools.map { dir -> dir.file("zipalign").takeIf { it.asFile.exists() } })
            report.set(layout.buildDirectory.file("reports/gates/$variantName-16kb.txt"))
        }
        tasks.named("check") { dependsOn(size, align) }
    }
}

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        extensions.configure<LibraryExtension> {
            configureAndroidCommon(this)
            testOptions.targetSdk = Sdk.TARGET
            lint.targetSdk = Sdk.TARGET
        }
    }
}

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
        val android = extensions.getByName("android") as CommonExtension
        android.buildFeatures.compose = true
        dependencies {
            val bom = platform(libs.findLibrary("androidx-compose-bom").get())
            add("implementation", bom)
            add("androidTestImplementation", bom)
            add("implementation", libs.findLibrary("androidx-compose-ui").get())
            add("implementation", libs.findLibrary("androidx-compose-foundation").get())
            add("implementation", libs.findLibrary("androidx-compose-material3").get())
            add("implementation", libs.findLibrary("androidx-compose-animation").get())
            add("implementation", libs.findLibrary("androidx-compose-ui-tooling-preview").get())
            add("debugImplementation", libs.findLibrary("androidx-compose-ui-tooling").get())
        }
    }
}

class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }
        configureKotlin()
    }
}
