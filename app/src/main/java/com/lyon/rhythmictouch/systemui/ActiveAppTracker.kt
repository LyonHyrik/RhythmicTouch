package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Process
import com.lyon.rhythmictouch.RhythmicConstants
import java.lang.reflect.Method

class ActiveAppTracker(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val packageManager = context.packageManager
    private val uidCache = HashMap<Int, String>()

    @Volatile
    var activeUids: List<Int> = emptyList()
        private set

    @Volatile
    var activeSessions: List<Int> = emptyList()
        private set

    private var lastRefreshMs = 0L

    var hasAAudioApps = false
        private set
    
    fun refresh(nowMs: Long = System.currentTimeMillis()) {
        if (nowMs - lastRefreshMs < REFRESH_INTERVAL_MS) return
        lastRefreshMs = nowMs
        val uids = mutableListOf<Int>()
        val sessions = mutableListOf<Int>()
        var detectedAAudio = false
        
        try {
            val configs = audioManager?.getActivePlaybackConfigurations()
            log("🎵 Found ${configs?.size ?: 0} active playback configurations")
            
            if (configs.isNullOrEmpty()) {
                log("⚠️ No active playback configurations found!")
            }
            
            configs?.forEachIndexed { index, cfg ->
                val uid = clientUidOf(cfg)
                if (uid <= 0 || uid == Process.SYSTEM_UID) return@forEachIndexed
                
                val str = try {
                    cfg.toString()
                } catch (t: Throwable) {
                    log("❌ Failed to get config string at index $index: ${t.message}")
                    return@forEachIndexed
                }
                
                val pkgName = packageForUid(uid)
                log("🔍 [$index] Audio config: uid=$uid pkg=$pkgName raw=$str")
                
                if (parseState(str) != "started") {
                    log("⏭️ Skipping non-started state: ${parseState(str)}")
                    return@forEachIndexed
                }
                
                val session = parseSession(str)
                val isAAudio = "AAudio" in str
                val hasInvalidSession = "sessionId:-1" in str
                
                log("🔎 Parsed: type=${if (isAAudio) "AAudio" else "Other"}, session=$session, invalidSession=$hasInvalidSession")
                
                if (isAAudio && hasInvalidSession) {
                    log("🎮🎮🎮 Detected AAudio app: $pkgName (uid=$uid, sessionId=-1) 🎮🎮🎮")
                    detectedAAudio = true
                    uids += uid
                    sessions += -9999 
                    log("✅✅✅ AAudio app added with special session ID (-9999) ✅✅✅")
                } else if (session > 0) {
                    uids += uid
                    sessions += session
                    log("✅ Active session: $session for $pkgName (uid=$uid)")
                } else {
                    log("⏭️ Skipped: session=$session (not > 0 and not AAudio)")
                }
            }
            
            hasAAudioApps = detectedAAudio
            
            if (uids.isNotEmpty()) {
                if (uids != activeUids || sessions != activeSessions || detectedAAudio != hasAAudioApps) {
                    log("📋 Session list updated -> uids=$uids sessions=$sessions pkgs=${uids.map { packageForUid(it) }} aAudio=$detectedAAudio")
                }
            } else {
                if (activeUids.isNotEmpty()) {
                    log("⚠️ No active sessions (was: sessions=$activeSessions)")
                }
                hasAAudioApps = false
            }
        } catch (t: Throwable) {
            log("getActivePlaybackConfigurations failed: $t")
        }
        activeUids = uids
        activeSessions = sessions
    }

    fun primarySessionId(): Int {
        // Priority 1: Return AAudio session (-9999) if detected (for Phira support)
        val aaudioIndex = activeSessions.indexOf(-9999)
        if (aaudioIndex >= 0) {
            val pkg = packageForUid(activeUids.getOrNull(aaudioIndex) ?: 0)
            log("🎯 primarySessionId()=-9999 (AAudio mode) for package=$pkg → Will use Global Visualizer!")
            return -9999
        }
        
        // Priority 2: Return first normal session
        val sessionId = activeSessions.firstOrNull() ?: 0
        if (sessionId > 0) {
            val pkg = packageForUid(activeUids.firstOrNull() ?: 0)
            log("🎯 primarySessionId()=$sessionId for package=$pkg")
        }
        return sessionId
    }

    fun isBlocked(whitelistMode: Boolean, scopeApps: Set<String>): Boolean {
        val activePkgs = activeUids.mapNotNull { packageForUid(it) }.distinct()
        return if (whitelistMode) {
            activePkgs.isNotEmpty() && activePkgs.none { it in scopeApps }
        } else {
            activePkgs.any { it in scopeApps }
        }
    }

    fun primaryApp(): String? {
        for (uid in activeUids) {
            val pkg = packageForUid(uid)
            if (pkg != null && pkg != RhythmicConstants.SYSTEMUI_PACKAGE) return pkg
        }
        return null
    }

    private fun packageForUid(uid: Int): String? {
        uidCache[uid]?.let { return it }
        val name = try {
            packageManager.getNameForUid(uid)
        } catch (t: Throwable) {
            null
        }
        if (name != null) uidCache[uid] = name
        return name
    }

    private fun clientUidOf(cfg: AudioPlaybackConfiguration): Int =
        invokeSafe(uidMethod) { it.invoke(cfg) as Int } ?: -1

    private fun parseState(str: String): String =
        SESSION_RE.find(str)?.groupValues?.get(1) ?: ""

    private fun parseSession(str: String): Int =
        SESSION_ID_RE.find(str)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private inline fun <T> invokeSafe(method: Method?, block: (Method) -> T): T? {
        if (method == null) return null
        return try {
            block(method)
        } catch (t: Throwable) {
            null
        }
    }

    private fun log(msg: String) {
        RhythmicLog.x(TAG, msg)
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 500L
        const val TAG = "RhythmicTouch"

        val SESSION_RE = Regex("state:(\\w+)")
        val SESSION_ID_RE = Regex("sessionId:(\\d+)")

        val uidMethod: Method? by lazy {
            try {
                AudioPlaybackConfiguration::class.java.getMethod("getClientUid")
            } catch (t: Throwable) {
                null
            }
        }
    }
}