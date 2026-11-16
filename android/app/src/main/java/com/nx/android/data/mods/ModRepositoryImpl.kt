package com.nx.android.data.mods

import android.content.Context
import com.nx.android.domain.mods.Mod
import com.nx.android.domain.mods.ModImportResult
import com.nx.android.domain.mods.ModRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ModRepository {

    private val titleIdRegex = Regex("^[0-9A-Fa-f]{16}$")
    private val maxBytes = 2L * 1024 * 1024 * 1024
    private val maxEntries = 100_000

    private fun modsRoot() = File(context.filesDir, "mods").also { it.mkdirs() }
    private fun modsRootFor(titleId: String) = File(modsRoot(), titleId).also { it.mkdirs() }

    override fun list(titleId: String): List<Mod> =
        modsRootFor(titleId).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { load(it) }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()

    override fun setEnabled(mod: Mod, enabled: Boolean): Mod {
        val updated = mod.copy(enabled = enabled)
        save(updated)
        return updated
    }

    override fun delete(mod: Mod): Boolean = mod.directory.deleteRecursively()

    override fun importZip(titleId: String, input: InputStream, displayName: String?): ModImportResult =
        try {
            requireTitleId(titleId)
            val temp = File(context.cacheDir, "mod-import-${System.nanoTime()}").also { it.mkdirs() }
            try {
                extractSafely(input, temp)
                finalise(temp, displayName, titleId)
            } finally {
                temp.deleteRecursively()
            }
        } catch (e: Exception) {
            ModImportResult.Failed(e.message ?: "Import failed")
        }

    override fun importDirectory(titleId: String, sourceDir: File, displayName: String?): ModImportResult =
        try {
            requireTitleId(titleId)
            finalise(sourceDir, displayName, titleId)
        } catch (e: Exception) {
            ModImportResult.Failed(e.message ?: "Import failed")
        }

    // --- internals ----------------------------------------------------------

    private fun requireTitleId(titleId: String) {
        require(titleIdRegex.matches(titleId)) { "Invalid Title ID: '$titleId'" }
    }

    private fun extractSafely(input: InputStream, dest: File) {
        val destCanon = dest.canonicalPath + File.separator
        var total = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > maxEntries) error("Too many files in mod (>$maxEntries)")
                val name = entry.name
                if (name.contains("..") || name.startsWith("/") || name.startsWith("\\"))
                    error("Illegal path in mod: '$name'")
                val out = File(dest, name)
                if (!out.canonicalPath.startsWith(destCanon) && out.canonicalPath != dest.canonicalPath)
                    error("Path traversal in mod: '$name'")
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os -> zip.copyTo(os) }
                    total += out.length()
                    if (total > maxBytes) error("Mod exceeds ${maxBytes / (1024 * 1024)} MiB")
                }
                zip.closeEntry()
            }
        }
    }

    private fun findModRoot(extracted: File, titleId: String): File {
        if (hasRomfsOrExefs(extracted)) return extracted
        val contents = File(extracted, "contents")
        if (contents.isDirectory) {
            File(contents, titleId).takeIf { hasRomfsOrExefs(it) }?.let { return it }
            contents.listFiles()?.forEach { if (hasRomfsOrExefs(it)) return it }
        }
        extracted.listFiles()?.forEach { if (hasRomfsOrExefs(it)) return it }
        error("Mod contains neither romfs/ nor exefs/")
    }

    private fun hasRomfsOrExefs(dir: File) =
        File(dir, "romfs").isDirectory || File(dir, "exefs").isDirectory

    private fun finalise(extracted: File, rawName: String?, titleId: String): ModImportResult {
        val root = findModRoot(extracted, titleId)
        val folderName = sanitise(rawName) ?: "mod-${System.currentTimeMillis()}"
        val dest = File(modsRootFor(titleId), folderName)
        if (dest.exists()) return ModImportResult.Failed("A mod named '$folderName' already exists for this game")
        if (!root.renameTo(dest)) root.copyRecursively(dest, overwrite = false)
        val mod = Mod(
            name = rawName?.trim()?.takeIf { it.isNotEmpty() } ?: folderName,
            author = null,
            version = null,
            titleId = titleId,
            enabled = true,
            hasRomfs = File(dest, "romfs").isDirectory,
            hasExefs = File(dest, "exefs").isDirectory,
            directory = dest,
        )
        save(mod)
        return ModImportResult.Ok(mod)
    }

    private fun sanitise(raw: String?): String? {
        if (raw == null) return null
        val cleaned = raw
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
            .replace(Regex("\\.+"), ".")
            .trim('.', ' ', '_')
        return cleaned.takeIf { it.isNotEmpty() && it != "." && it != ".." }
    }

    private fun save(mod: Mod) {
        val json = JSONObject().apply {
            put("name", mod.name)
            mod.author?.let { put("author", it) }
            mod.version?.let { put("version", it) }
            put("titleId", mod.titleId)
            put("enabled", mod.enabled)
            put("hasRomfs", mod.hasRomfs)
            put("hasExefs", mod.hasExefs)
        }
        File(mod.directory, "mod.json").writeText(json.toString(2))
    }

    private fun load(dir: File): Mod? {
        val meta = File(dir, "mod.json")
        if (!meta.isFile) return null
        return try {
            val json = JSONObject(meta.readText())
            Mod(
                name = json.optString("name", dir.name),
                author = json.optString("author").ifBlank { null },
                version = json.optString("version").ifBlank { null },
                titleId = json.optString("titleId", dir.parentFile?.name ?: ""),
                enabled = json.optBoolean("enabled", true),
                hasRomfs = File(dir, "romfs").isDirectory,
                hasExefs = File(dir, "exefs").isDirectory,
                directory = dir,
            )
        } catch (_: Exception) { null }
    }
}
