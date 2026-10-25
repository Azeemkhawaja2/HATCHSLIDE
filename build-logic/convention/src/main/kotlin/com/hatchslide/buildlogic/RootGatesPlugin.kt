package com.hatchslide.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.register

/**
 * Repository-wide gates wired into the root `check` task:
 *  - verifyNoNetworkCode: no networking classes or WebView anywhere in shipped or tool sources.
 *  - verifyNoTodo: no TODO markers in any src/main tree.
 *  - ktlintCheck: ktlint over every Kotlin source and build script.
 */
class RootGatesPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("base")

        val sourceTrees = fileTree(rootDir) {
            include("app/src/**", "core/*/src/**", "feature/*/src/**", "baselineprofile/src/**", "tools/levelgen/src/**")
            include("**/*.gradle.kts")
            exclude("**/build/**", "**/.gradle/**", "build-logic/**")
            exclude("**/*.png", "**/*.webp", "**/*.ogg", "**/*.ktx", "**/*.glb", "**/*.filamat", "**/*.ttf", "**/*.json")
        }

        // Patterns are assembled from parts so this file never matches its own gate.
        val networkPatterns = listOf(
            "java" + "\\.net\\b",
            "(?i)ok" + "http",
            "Http" + "URLConnection",
            "Web" + "View",
        )
        val noNetwork = tasks.register<SourceScanTask>("verifyNoNetworkCode") {
            group = "verification"
            description = "Fails if any source uses networking APIs or WebView."
            // The app manifest names network permissions only to remove them; the merged-manifest gate covers it.
            sources.from(sourceTrees.matching { exclude("app/src/main/AndroidManifest.xml") })
            patterns.set(networkPatterns)
            gateName.set("No network code")
            report.set(layout.buildDirectory.file("reports/gates/no-network.txt"))
        }

        val noTodo = tasks.register<SourceScanTask>("verifyNoTodo") {
            group = "verification"
            description = "Fails if any src/main file contains a TODO marker."
            sources.from(fileTree(rootDir) {
                include("app/src/main/**", "core/*/src/main/**", "feature/*/src/main/**", "tools/*/src/main/**")
                include("tools/assetgen/**/*.py", "materials/**")
                exclude("**/build/**", "**/assets/**", "**/res/font/**")
                exclude("**/*.png", "**/*.webp", "**/*.ogg", "**/*.ktx", "**/*.glb", "**/*.filamat", "**/*.ttf")
            })
            patterns.set(listOf("\\b" + "TODO\\b", "\\bFIX" + "ME\\b"))
            gateName.set("No TODO")
            report.set(layout.buildDirectory.file("reports/gates/no-todo.txt"))
        }

        val ktlint = configurations.create("ktlint") {
            isCanBeConsumed = false
            isCanBeResolved = true
            attributes {
                attribute(
                    org.gradle.api.attributes.Category.CATEGORY_ATTRIBUTE,
                    objects.named(org.gradle.api.attributes.Category::class.java, org.gradle.api.attributes.Category.LIBRARY),
                )
                attribute(
                    org.gradle.api.attributes.LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                    objects.named(org.gradle.api.attributes.LibraryElements::class.java, org.gradle.api.attributes.LibraryElements.JAR),
                )
                attribute(
                    org.gradle.api.attributes.Usage.USAGE_ATTRIBUTE,
                    objects.named(org.gradle.api.attributes.Usage::class.java, org.gradle.api.attributes.Usage.JAVA_RUNTIME),
                )
                attribute(
                    org.gradle.api.attributes.Bundling.BUNDLING_ATTRIBUTE,
                    objects.named(org.gradle.api.attributes.Bundling::class.java, org.gradle.api.attributes.Bundling.EXTERNAL),
                )
            }
        }
        dependencies { add(ktlint.name, libs.findLibrary("ktlint-cli").get()) }

        val ktlintArgs = listOf(
            "app/src/**/*.kt", "core/*/src/**/*.kt", "feature/*/src/**/*.kt", "baselineprofile/src/**/*.kt",
            "tools/levelgen/src/**/*.kt", "**/*.gradle.kts", "!**/build/**",
        )
        val ktlintCheck = tasks.register<JavaExec>("ktlintCheck") {
            group = "verification"
            description = "Runs ktlint over all Kotlin sources."
            classpath = ktlint
            mainClass.set("com.pinterest.ktlint.Main")
            workingDir = rootDir
            args(listOf("--relative") + ktlintArgs)
        }
        tasks.register<JavaExec>("ktlintFormat") {
            group = "formatting"
            description = "Auto-formats Kotlin sources with ktlint."
            classpath = ktlint
            mainClass.set("com.pinterest.ktlint.Main")
            workingDir = rootDir
            args(listOf("-F", "--relative") + ktlintArgs)
        }

        tasks.named("check") {
            dependsOn(noNetwork, noTodo, ktlintCheck)
        }
        Unit
    }
}
