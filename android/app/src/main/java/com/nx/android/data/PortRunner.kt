package com.nx.android.data

import com.nx.android.BuildConfig
import com.nx.android.R
import com.nx.android.domain.GameIdentity
import com.nx.android.domain.GameSource
import com.nx.android.domain.environment.EnvironmentSetup
import com.nx.android.domain.environment.GuestProcessLauncher
import com.nx.android.data.mods.ModOverlayEngine
import java.io.File

/**
 * Runs the porter and the doctor: the process handling that used to live in the activity, unchanged in its
 * behavior (one stream for stdout and stderr, the porter's events by line, cancel stops the porter and the
 * tools it started). Every run is one thread; the output goes to [onLine] from that thread.
 */
class PortRunner(
    private val environmentSetup: EnvironmentSetup,
    private val guestLauncher: GuestProcessLauncher,
    private val locale: AppLocale,
    private val statusFile: File? = null,
    private val onLine: (String) -> Unit,
) {

    @Volatile private var cancelled = false
    @Volatile private var proc: Process? = null

    fun port(
        gameFile: String, keys: String,
        touch: Boolean, fps60: Boolean, experimental: Boolean,
        aliasHeadroom: Int,
        sharedFont: String = "", firmware: String = "", mtReplay: Boolean = false,
        packDir: String? = null,
        modsDir: String? = null,
        onEnd: () -> Unit,
    ): Thread {
        val args = portArgs(gameFile, keys, touch, fps60, experimental, aliasHeadroom, !BuildConfig.NX_LSFG,
            sharedFont, firmware, mtReplay, deviceLanguage())
        
        GameSource.cloudFolder(gameFile)?.let { onLine(locale.str(R.string.runner_cloud_folder, it)) }
        
        val extraMap = mutableMapOf<String, String>()
        if (!packDir.isNullOrBlank()) extraMap["NX2A_PACK_DIR"] = packDir
        if (!modsDir.isNullOrBlank()) extraMap["NX2A_MODS_DIR"] = modsDir
        
        val titleId = GameIdentity.titleIdIn(File(gameFile).name)
        
        return start(args, onEnd, extraMap, titleId, modsDir, packDir)
    }

    private fun deviceLanguage(): String = java.util.Locale.getDefault().toLanguageTag()

    companion object {
        fun portArgs(gameFile: String, keys: String, touch: Boolean, fps60: Boolean,
                     experimental: Boolean, aliasHeadroom: Int, noLsfg: Boolean,
                     sharedFont: String = "", firmware: String = "", mtReplay: Boolean = false,
                     deviceLanguage: String = ""): List<String> {
            val args = mutableListOf("-m", "porter", "port", gameFile, "-k", keys, "--progress", "json", "--app")
            if (touch) args += "--touch"
            if (fps60) args += "--fps60"
            if (experimental) args += "--experimental"
            if (aliasHeadroom >= 0) args += listOf("--alias-headroom", aliasHeadroom.toString())
            if (sharedFont.isNotBlank()) args += listOf("--shared-font", sharedFont.trim())
            if (firmware.isNotBlank()) args += listOf("--firmware", firmware.trim())
            if (mtReplay) args += "--mt-replay"
            if (deviceLanguage.isNotBlank()) args += listOf("--language-preferred", deviceLanguage.trim())
            if (noLsfg) args += "--no-lsfg"
            return args
        }
    }

    fun doctor(keys: String, json: Boolean, onEnd: () -> Unit): Thread {
        val args = mutableListOf("-m", "porter", "doctor")
        if (json) args += "--json"
        if (keys.isNotBlank()) args += listOf("-k", keys.trim())
        val t = Thread {
            try {
                onLine(locale.str(R.string.runner_checking_tools))
                environmentSetup.patchInterpreters()
                onLine(locale.str(R.string.runner_interpreter_patched))
            } catch (e: Exception) {
                onLine(locale.str(R.string.runner_interpreter_patch_failed, e.message))
            }
            start(args, onEnd)
        }
        t.start()
        return t
    }

    private fun start(
        args: List<String>, 
        onEnd: () -> Unit, 
        extra: Map<String, String> = emptyMap(),
        titleId: String? = null,
        modsDir: String? = null,
        packDir: String? = null
    ): Thread {
        val t = Thread {
            try {
                if (cancelled) return@Thread
                val p = guestLauncher.guest(args, extra = extra)
                proc = p
                if (cancelled) {
                    p.destroyForcibly()
                    return@Thread
                }
                p.inputStream.bufferedReader().forEachLine { onLine(it) }
                val code = runCatching { p.waitFor() }.getOrDefault(-1)
                
                if (code != 0) {
                    onLine(exitLine(code))
                } else if (titleId != null && modsDir != null && packDir != null) {
                    // THE FINAL HOOK: Apply mods after the porter successfully finishes
                    val pack = File(packDir)
                    val romfs = File(pack, "romfs")
                    val exefs = File(pack, "exefs")
                    
                    if (romfs.isDirectory) {
                        onLine("Applying mods...")
                        try {
                            val report = ModOverlayEngine.applyPrePort(
                                titleId = titleId,
                                modsDir = File(modsDir),
                                extractedRomfs = romfs,
                                extractedExefs = exefs.takeIf { it.isDirectory }
                            )
                            report.logLines.forEach { onLine(it) }
                            onLine("Mods applied successfully.")
                        } catch (e: Exception) {
                            onLine("[RED] Failed to apply mods: ${e.message}")
                        }
                    }
                }
                
                statusFile?.let { writeStatus(it, code) }
            } catch (e: Exception) {
                onLine("[RED] ${e.message}")
            } finally {
                proc = null
                onEnd()
            }
        }
        t.start()
        return t
    }

    private fun exitLine(code: Int): String {
        val sig = signal(code)
        return if (sig != 0) locale.str(R.string.runner_killed, sig) else locale.str(R.string.runner_exit_code, code)
    }

    private fun signal(code: Int): Int = if (code in 129..255) code - 128 else 0

    private fun writeStatus(file: File, code: Int) {
        try {
            file.parentFile?.mkdirs()
            val sig = signal(code)
            val what = if (sig != 0) "killed by signal $sig" else "exit code $code"
            file.writeText("port: $what\ntime: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n")
        } catch (e: Exception) {
        }
    }

    fun cancel() {
        cancelled = true
        try { proc?.destroyForcibly() } catch (e: Exception) {
        }
        proc = null
        val me = android.os.Process.myPid()
        for (d in File("/proc").listFiles() ?: return) {
            if (!d.name.all { it.isDigit() }) continue
            val pid = d.name.toLong()
            if (pid == me.toLong()) continue
            try {
                val cmd = File(d, "cmdline").readText()
                if (cmd.contains("python3.13") || cmd.contains("dotnet") || cmd.contains("ld-linux") ||
                    cmd.contains("clang") || cmd.contains("nx_extract")
                ) {
                    android.os.Process.killProcess(pid.toInt())
                }
            } catch (e: Exception) {
            }
        }
    }
}
