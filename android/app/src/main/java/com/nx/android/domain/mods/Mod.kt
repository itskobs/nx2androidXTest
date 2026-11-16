package com.nx.android.domain.mods

import java.io.File

/**
 * One imported mod for a game. A mod is a LayeredFS-style folder: it carries
 * a `romfs/` and/or `exefs/` subtree that is overlaid on the ported data pack.
 */
data class Mod(
    val name: String,
    val author: String?,
    val version: String?,
    val titleId: String,
    val enabled: Boolean,
    val hasRomfs: Boolean,
    val hasExefs: Boolean,
    /** The mod's own directory; contains `romfs/` and/or `exefs/`. */
    val directory: File,
) {
    val displayName: String
        get() = listOfNotNull(name, version?.let { "v$it" }).joinToString(" ")
}
