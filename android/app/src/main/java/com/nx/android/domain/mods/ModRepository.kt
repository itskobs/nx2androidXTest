package com.nx.android.domain.mods

import java.io.File
import java.io.InputStream

/** Outcome of an import attempt; the caller turns failures into a user-facing message. */
sealed interface ModImportResult {
    data class Ok(val mod: Mod) : ModImportResult
    data class Failed(val reason: String) : ModImportResult
}

/**
 * The mod store for one game. All filesystem work happens behind this interface;
 * the implementation lives in `data.mods` and is Hilt-injected.
 */
interface ModRepository {
    fun list(titleId: String): List<Mod>
    fun setEnabled(mod: Mod, enabled: Boolean): Mod
    fun delete(mod: Mod): Boolean

    /** Import a ZIP stream. [displayName] is used as the mod folder name if usable. */
    fun importZip(titleId: String, input: InputStream, displayName: String?): ModImportResult

    /** Import an already-materialised directory tree (the caller copies the SAF tree to a temp dir first). */
    fun importDirectory(titleId: String, sourceDir: File, displayName: String?): ModImportResult
}
