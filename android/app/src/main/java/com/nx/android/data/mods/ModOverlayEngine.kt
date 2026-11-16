package com.nx.android.data.mods

import com.nx.android.domain.mods.Mod
import org.json.JSONObject
import java.io.File

data class MergeReport(
    val modsApplied: Int,
    val filesOverwritten: Int,
    val logLines: List<String>,
)

object ModOverlayEngine {

    fun applyPrePort(
        titleId: String,
        modsDir: File?,
        extractedRomfs: File,
        extractedExefs: File? = null,
    ): MergeReport {
        if (modsDir == null || !modsDir.isDirectory) return MergeReport(0, 0, emptyList())

        // Read enabled mods directly from the directory
        val mods = modsDir.listFiles()?.filter { it.isDirectory }?.mapNotNull { dir ->
            val meta = File(dir, "mod.json")
            if (!meta.isFile) return@mapNotNull null
            try {
                val json = JSONObject(meta.readText())
                if (!json.optBoolean("enabled", true)) return@mapNotNull null
                Mod(
                    name = json.optString("name", dir.name),
                    author = json.optString("author").ifBlank { null },
                    version = json.optString("version").ifBlank { null },
                    titleId = json.optString("titleId", titleId),
                    enabled = true,
                    hasRomfs = File(dir, "romfs").isDirectory,
                    hasExefs = File(dir, "exefs").isDirectory,
                    directory = dir,
                )
            } catch (_: Exception) { null }
        } ?: emptyList()

        if (mods.isEmpty()) return MergeReport(0, 0, listOf("No enabled mods for $titleId"))
        require(extractedRomfs.isDirectory) { "extractedRomfs missing: $extractedRomfs" }

        var files = 0
        val log = mutableListOf<String>()
        for (mod in mods) {
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
        return MergeReport(mods.size, files, log)
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
