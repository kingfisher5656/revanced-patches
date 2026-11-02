package app.morphe.tools

import app.morphe.patcher.Patcher
import app.morphe.patcher.PatcherConfig
import app.morphe.patcher.apk.ApkUtils
import app.morphe.patcher.patch.Patch
import app.morphe.patcher.patch.loadPatchesFromJar
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.exitProcess

/**
 * Patches an APK on this machine, for testing without Morphe Manager.
 *
 * Arguments: <apk> <patches.mpp> <output.apk> [keystore keystorePassword alias keyPassword] [--list] [--exclude=Name,Name]
 * Applies every patch that is enabled by default and compatible with the APK's package.
 */
fun main(args: Array<String>) {
    // "-" stands for an empty argument, which Gradle's --args cannot pass.
    val positional = args.filterNot { it.startsWith("--") }.map { if (it == "-") "" else it }
    val apk = File(positional[0])
    val patchesFile = File(positional[1])
    val output = File(positional[2])
    val exclude = args.firstOrNull { it.startsWith("--exclude=") }
        ?.removePrefix("--exclude=")?.split(',')?.map { it.trim() }?.toSet().orEmpty()

    val packageName = "com.dcinside.app.android"
    val patches = loadPatchesFromJar(setOf(patchesFile))
    val selected = patches.filter { patch ->
        patch.name != null && patch.default && patch.name !in exclude && patch.isCompatibleWith(packageName)
    }.toSet()

    println("Selected ${selected.size} patches:")
    selected.sortedBy { it.name }.forEach { println("  ${it.name}") }
    if ("--list" in args) return

    val workDir = File(output.parentFile, "patcher-tmp").apply { deleteRecursively() }
    var failed = false
    Patcher(PatcherConfig(apk, workDir)).use { patcher ->
        patcher += selected
        runBlocking {
            patcher().collect { result ->
                val error = result.exception
                if (error == null) {
                    println("OK    ${result.patch.name ?: "(dependency)"}")
                } else {
                    failed = true
                    println("FAIL  ${result.patch.name}: ${error.stackTraceToString()}")
                }
            }
        }
        if (failed) exitProcess(1)

        val unsigned = File(output.parentFile, output.nameWithoutExtension + "-unsigned.apk")
        apk.copyTo(unsigned, overwrite = true)
        with(ApkUtils) { patcher.get().applyTo(unsigned) }

        if (positional.size >= 7) {
            ApkUtils.signApk(
                unsigned,
                output,
                "Morphe",
                // Morphe Manager keeps the store password empty and passes it as null.
                ApkUtils.KeyStoreDetails(File(positional[3]), positional[4].ifEmpty { null }, positional[5], positional[6]),
            )
            unsigned.delete()
            println("Signed APK: $output")
        } else {
            println("Unsigned APK: $unsigned")
        }
    }
    workDir.deleteRecursively()
}

private fun Patch<*>.isCompatibleWith(packageName: String): Boolean {
    val packages = compatibility?.map { it.packageName }
    return packages == null || packages.any { it == null || it == packageName }
}
