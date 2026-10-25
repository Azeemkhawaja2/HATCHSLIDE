package com.hatchslide.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Release gate (spec sections 10 and 12): the merged release manifest must declare
 * targetSdk 37, minSdk 26, appCategory="game", no <uses-permission> of any kind,
 * no custom <permission>, and must not be debuggable.
 */
@CacheableTask
abstract class VerifyManifestTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @get:Input
    abstract val expectedTargetSdk: Property<Int>

    @get:Input
    abstract val expectedMinSdk: Property<Int>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val file = mergedManifest.get().asFile
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(file)
        val android = "http://schemas.android.com/apk/res/android"
        val problems = mutableListOf<String>()

        for (tag in listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")) {
            val nodes = doc.getElementsByTagName(tag)
            for (i in 0 until nodes.length) {
                val name = (nodes.item(i) as Element).getAttributeNS(android, "name")
                problems += "<$tag> declared: $name"
            }
        }
        val permissions = doc.getElementsByTagName("permission")
        for (i in 0 until permissions.length) {
            val name = (permissions.item(i) as Element).getAttributeNS(android, "name")
            problems += "<permission> declared: $name"
        }

        val usesSdk = doc.getElementsByTagName("uses-sdk").item(0) as Element?
        val target = usesSdk?.getAttributeNS(android, "targetSdkVersion")
        val min = usesSdk?.getAttributeNS(android, "minSdkVersion")
        if (target != expectedTargetSdk.get().toString()) {
            problems += "targetSdkVersion is '$target', expected ${expectedTargetSdk.get()}"
        }
        if (min != expectedMinSdk.get().toString()) {
            problems += "minSdkVersion is '$min', expected ${expectedMinSdk.get()}"
        }

        val application = doc.getElementsByTagName("application").item(0) as Element?
        if (application == null) {
            problems += "no <application> element"
        } else {
            val category = application.getAttributeNS(android, "appCategory")
            if (category != "game") problems += "android:appCategory is '$category', expected 'game'"
            if (application.getAttributeNS(android, "debuggable") == "true") {
                problems += "release manifest is debuggable"
            }
        }

        val summary = buildString {
            appendLine("Merged manifest: ${file.path}")
            appendLine("targetSdk=$target minSdk=$min")
            if (problems.isEmpty()) appendLine("OK: zero permissions, appCategory=game") else problems.forEach { appendLine("FAIL: $it") }
        }
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(summary)
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Manifest gate failed:\n" + problems.joinToString("\n") { " - $it" })
        }
        logger.lifecycle("Manifest gate passed: zero permissions, targetSdk=$target, appCategory=game")
    }
}
