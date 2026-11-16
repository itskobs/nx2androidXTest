package com.nx.android.data.mods

import com.nx.android.domain.mods.Mod
import java.io.File

data class MergeReport(
    val modsApplied: Int,
    val filesOverwritten: Int,
    val logLines: List<String>,
)

/**
 * Applies enabled mods onto an extracted game tree. Called from the port pipeline
 * after RomFS extraction and before the data pack is packaged.
 * With zero mods imported this is a no-op.
 */
object ModOverlayEngine {

    fun applyPrePort(
        titleId: String,
        mods: List<Mod>,
        extractedRomfs: File,
        extractedExefs: File? = null,
    ): MergeReport {
        val enabled = mods.filter { it.enabled && it.titleId == titleId }
        if (enabled.isEmpty()) return MergeReport(0, 0, listOf("No enabled mods for $titleId"))
        require(extractedRomfs.isDirectory) { "extractedRomfs missing: $extractedRomfs" }

        var files = 0
        val log = mutableListOf<String>()
        for (mod in enabled) {
            if (mod.hasRomfs) {
                val n = overlay(File(mod.directory, "romfs"), extractedRomfs)
                log += "romfs overlay: ${mod.displayName} ($n files)"
                files += n
            }
            if (mod.hasExefs && extractedExefs != null && extractedExefs.isDirectory) {
                val n = overlay(File(mod.directory, "exefs"), extractedExefs)
                log += "exefs overlay: ${mod.displayName} ($n files)"
                files += n
            }
        }
        return MergeReport(enabled.size, files, log)
    }

    private fun overlay(src: File, dst: File): Int {
        if (!src.isDirectory) return 0
        var count = 0
        src.walkTopDown().forEach { file ->
            if (file.isFile) {
                val target = File(dst, file.relativeTo(src).path)
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
                if (file.canExecute()) target.setExecutable(true, false)
                count++
            }
        }
        return count
    }
}
