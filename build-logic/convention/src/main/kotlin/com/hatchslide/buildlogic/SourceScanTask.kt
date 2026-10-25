package com.hatchslide.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Text gate over a set of source files: fails when any line matches one of [patterns].
 * Used for the no-network-code gate (spec section 10) and the no-TODO gate (section 12).
 */
@CacheableTask
abstract class SourceScanTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Regular expressions; a match on any line is a violation. */
    @get:Input
    abstract val patterns: ListProperty<String>

    @get:Input
    abstract val gateName: Property<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val regexes = patterns.get().map { Regex(it) }
        val hits = mutableListOf<String>()
        var scanned = 0
        sources.files.sortedBy { it.path }.forEach { file ->
            scanned++
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    if (regexes.any { it.containsMatchIn(line) }) {
                        hits += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }
        }
        val name = gateName.get()
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(
                if (hits.isEmpty()) "OK: $name, $scanned files scanned\n" else hits.joinToString("\n", postfix = "\n"),
            )
        }
        if (hits.isNotEmpty()) {
            throw GradleException("$name gate failed:\n" + hits.joinToString("\n"))
        }
        logger.lifecycle("$name gate passed ($scanned files scanned)")
    }
}
