package com.hatchslide.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Compiles Filament `.mat` sources with `matc` from the same Filament release as the runtime
 * (spec section 6). The tool is fetched by tools/filament/fetch_tools.py. Output lands in
 * `<outputDir>/materials/<name>.filamat` so it merges into the APK as `assets/materials/`.
 */
@CacheableTask
abstract class CompileMaterialsTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fetchScript: RegularFileProperty

    /** Filament version; part of the cache key so a version bump recompiles everything. */
    @get:Input
    abstract val filamentVersion: Property<String>

    @get:Input
    abstract val python: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @TaskAction
    fun compile() {
        val out = ByteArrayOutputStream()
        exec.exec {
            commandLine(python.get(), fetchScript.get().asFile.absolutePath, "--print-dir")
            standardOutput = out
        }
        val toolsDir = File(out.toString().trim())
        val matc = File(toolsDir, if (System.getProperty("os.name").startsWith("Windows")) "matc.exe" else "matc")
        val target = outputDir.get().asFile.resolve("materials").apply {
            deleteRecursively()
            mkdirs()
        }
        sources.files.filter { it.extension == "mat" }.sortedBy { it.name }.forEach { source ->
            exec.exec {
                commandLine(
                    matc.absolutePath,
                    "--platform", "mobile",
                    "--api", "opengl",
                    "--output", File(target, source.nameWithoutExtension + ".filamat").absolutePath,
                    source.absolutePath,
                )
            }
        }
        logger.lifecycle("Compiled ${target.listFiles()?.size ?: 0} Filament materials with matc ${filamentVersion.get()}")
    }
}
