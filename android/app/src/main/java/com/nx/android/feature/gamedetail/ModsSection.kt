package com.nx.android.feature.gamedetail

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nx.android.R
import com.nx.android.core.ui.LocalAppLocale
import com.nx.android.core.ui.components.Hint
import com.nx.android.core.ui.components.QuietButton
import com.nx.android.core.ui.theme.NxPalette
import com.nx.android.domain.mods.Mod
import com.nx.android.domain.mods.ModImportResult
import com.nx.android.domain.mods.ModRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun ModsSection(
    repository: ModRepository,
    titleId: String,
    onToast: (String) -> Unit,
) {
    val locale = LocalAppLocale.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mods by remember { mutableStateOf<List<Mod>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Mod?>(null) }
    var note by remember { mutableStateOf("") }

    fun reload() {
        scope.launch {
            mods = withContext(Dispatchers.IO) { repository.list(titleId) }
        }
    }
    LaunchedEffect(titleId) { reload() }

    // ZIP import: system file picker, same pattern SaveRows uses.
    val zipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val displayName = uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".zip")
            val result = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    repository.importZip(titleId, input, displayName)
                } ?: ModImportResult.Failed("Cannot open selected file")
            }
            busy = false
            when (result) {
                is ModImportResult.Ok -> { note = locale.str(R.string.lib_mods_imported); onToast(note); reload() }
                is ModImportResult.Failed -> { note = result.reason; onToast(note) }
            }
        }
    }

    // Folder import: SAF tree picker; copy tree into a temp dir, then hand it to the repository.
    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        if (treeUri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val temp = File(context.cacheDir, "mod-tree-${System.nanoTime()}").also { it.mkdirs() }
                try {
                    val tree = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                        ?: return@withContext ModImportResult.Failed("Cannot open selected folder")
                    copyTree(context, tree, temp)
                    repository.importDirectory(titleId, temp, tree.name)
                } catch (e: Exception) {
                    ModImportResult.Failed(e.message ?: "Import failed")
                } finally {
                    temp.deleteRecursively()
                }
            }
            busy = false
            when (result) {
                is ModImportResult.Ok -> { note = locale.str(R.string.lib_mods_imported); onToast(note); reload() }
                is ModImportResult.Failed -> { note = result.reason; onToast(note) }
            }
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Text(locale.str(R.string.lib_section_mods),
         style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

    if (mods.isEmpty()) {
        Hint(locale.str(R.string.lib_mods_empty), size = 11.sp)
    } else {
        mods.forEach { mod ->
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(mod.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        buildString {
                            if (mod.hasRomfs) append("RomFS ")
                            if (mod.hasExefs) append("ExeFS")
                            mod.author?.let { append(" · by $it") }
                        }.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = mod.enabled,
                    onCheckedChange = { checked ->
                        scope.launch {
                            val updated = withContext(Dispatchers.IO) { repository.setEnabled(mod, checked) }
                            mods = mods.map { if (it.directory == updated.directory) updated else it }
                        }
                    },
                    enabled = !busy,
                )
                QuietButton(locale.str(R.string.common_delete), onClick = { pendingDelete = mod },
                    modifier = Modifier.widthIn(max = 90.dp), enabled = !busy)
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        QuietButton(locale.str(R.string.lib_mods_import_zip),
            onClick = { zipLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
            modifier = Modifier.weight(1f), enabled = !busy)
        QuietButton(locale.str(R.string.lib_mods_import_folder),
            onClick = { folderLauncher.launch(null) },
            modifier = Modifier.weight(1f), enabled = !busy)
    }
    if (note.isNotEmpty()) Hint(note, size = 11.sp)
    Hint(locale.str(R.string.lib_mods_rebuild_hint), size = 11.sp)

    pendingDelete?.let { mod ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(locale.str(R.string.lib_mods_delete_confirm)) },
            text = { Text(mod.displayName) },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        withContext(Dispatchers.IO) { repository.delete(mod) }
                        reload()
                    }
                }) { Text(locale.str(R.string.common_delete), color = NxPalette.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(locale.str(R.string.common_cancel)) }
            },
        )
    }
}

private fun copyTree(context: android.content.Context, node: androidx.documentfile.provider.DocumentFile, dest: File) {
    dest.mkdirs()
    for (child in node.listFiles()) {
        val name = child.name ?: continue
        val target = File(dest, name)
        if (child.isDirectory) {
            copyTree(context, child, target)
        } else {
            context.contentResolver.openInputStream(child.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
