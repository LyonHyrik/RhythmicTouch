package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.util.Log
import java.io.File

object DaemonManager {
    private const val TAG = "RhythmicDaemon"
    private const val UID_FILE = "/data/local/tmp/rhythmic_uids.txt"
    private const val DAEMON_BIN = "/data/local/tmp/rhythmic_daemon"
    private const val HOOK_LIB = "/data/local/tmp/librhythmictouch_hook.so"

    @Volatile
    private var started = false

    @Volatile
    private var daemonUids: Set<Int> = emptySet()

    private var lastReadMs = 0L

    fun start(context: Context) {
        if (started) return
        started = true
        try {
            Thread {
                try {
                    copyBinaries(context)
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "(setsid $DAEMON_BIN &)"))
                    process.waitFor()
                    Log.i(TAG, "daemon launch command executed")
                } catch (e: Exception) {
                    Log.e(TAG, "start failed: ${e.message}")
                }
            }.apply { name = "rhythmic-daemon-starter" }.start()
        } catch (e: Exception) {
            Log.e(TAG, "start threw: ${e.message}")
        }
    }

    private fun copyBinaries(context: Context) {
        try {
            val abi = if (android.os.Build.SUPPORTED_ABIS.isNotEmpty()) android.os.Build.SUPPORTED_ABIS[0] else "arm64-v8a"
            val nativeDir = File(context.applicationInfo.nativeLibraryDir)
            val daemonSrc = File(nativeDir, "rhythmic_daemon")
            val hookSrc = File(nativeDir, "librhythmic-hook.so")
            if (daemonSrc.exists()) {
                copyFile(daemonSrc, File(DAEMON_BIN))
                Log.i(TAG, "copied daemon from nativeLib ($abi)")
            } else {
                extractAsset(context, "bin/$abi/rhythmic_daemon", DAEMON_BIN)
            }
            if (hookSrc.exists()) {
                copyFile(hookSrc, File(HOOK_LIB))
                Log.i(TAG, "copied hook lib from nativeLib")
            } else {
                extractAsset(context, "bin/$abi/librhythmic-hook.so", HOOK_LIB)
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyBinaries failed: ${e.message}")
        }
    }

    private fun extractAsset(context: Context, assetPath: String, destPath: String) {
        try {
            context.assets.open(assetPath).use { input ->
                val tmp = File(destPath + ".new")
                tmp.outputStream().use { input.copyTo(it) }
                Runtime.getRuntime().exec(arrayOf("su", "-c", "cp ${tmp.absolutePath} $destPath && chmod 755 $destPath")).waitFor()
                tmp.delete()
                Log.i(TAG, "extracted asset $assetPath")
            }
        } catch (e: Exception) {
            Log.e(TAG, "extract asset $assetPath failed: ${e.message}")
        }
    }

    private fun copyFile(src: File, dst: File) {
        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "cp ${src.absolutePath} ${dst.absolutePath} && chmod 755 ${dst.absolutePath}")).waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "copy $src -> $dst failed: ${e.message}")
        }
    }

    fun refresh(): Set<Int> {
        val now = System.currentTimeMillis()
        if (now - lastReadMs < 1000) return daemonUids
        lastReadMs = now
        try {
            val f = File(UID_FILE)
            if (!f.exists()) return daemonUids
            val text = f.readText().trim()
            if (text.isEmpty()) return daemonUids
            val uids = text.split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .filter { it > 1000 }
                .toSet()
            if (uids.isNotEmpty()) daemonUids = uids
        } catch (e: Exception) {
            Log.e(TAG, "read uids failed: ${e.message}")
        }
        return daemonUids
    }
}
