package com.lyon.rhythmictouch.systemui

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Process
import android.os.SystemClock
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

    @Volatile
    var daemonUids: Set<Int> = emptySet()

    // Whitelist/blacklist verdict memory + grace tracking. See isBlocked()/currentPackages().
    @Volatile private var lastBlocked: Boolean = false
    @Volatile private var lastBlockedAtMs: Long = 0L
    @Volatile private var lastKnownPkgs: List<String> = emptyList()
    @Volatile private var lastKnownPkgsAtMs: Long = 0L

    /**
     * sessionId -> packageName, learned from getActivePlaybackConfigurations().
     * Lets isBlocked() resolve identity from the session the Visualizer is actually attached to,
     * instead of trusting the momentary active-playback snapshot.
     */
    private val sessionPkgMap = HashMap<Int, String>()

    /** Remember a session->package binding. Sessions are recycled by the audio server, so we
     *  refresh the timestamp every time we observe it rather than trusting a stale entry. */
    private fun rememberSessionPkg(session: Int, pkg: String) {
        if (session <= 0) return
        synchronized(sessionPkgMap) {
            sessionPkgMap[session] = pkg
            sessionSeenAtMs[session] = SystemClock.elapsedRealtime()
        }
    }

    private val sessionSeenAtMs = HashMap<Int, Long>()

    /** Resolve the package for a session, if we have seen it recently enough. */
    fun packageForSession(session: Int): String? {
        if (session <= 0) return null
        synchronized(sessionPkgMap) {
            val pkg = sessionPkgMap[session] ?: return null
            val seen = sessionSeenAtMs[session] ?: return null
            return if (SystemClock.elapsedRealtime() - seen < SESSION_GRACE_MS) pkg else null
        }
    }

    val mergedActiveUids: List<Int>
        get() = activeUids.distinct()

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
                
                // Accept 'created'/'paused' too. During the first frames of playback the state is often not
                // yet 'started', and filtering on it delays recognition until the first beat has
                // already leaked past the whitelist. Only an explicitly unparsed state is skipped.
                val state = parseState(str)
                if (state.isNotEmpty() && state == "stopped") {
                    log("⏭️ Skipping stopped config at index=$index")
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
                    // AAudio reports sessionId -1; bind the synthetic -9999 so isBlocked can
                    // still resolve identity for AAudio-only players (e.g. Phira).
                    pkgName?.let { rememberSessionPkg(AAUDIO_SESSION, it) }
                    log("✅✅✅ AAudio app added with special session ID (-9999) ✅✅✅")
                } else if (session > 0) {
                    uids += uid
                    sessions += session
                    pkgName?.let { rememberSessionPkg(session, it) }
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
        val distinctApps = mergedActiveUids
            .filterNot { isSystemUid(it) }
            .mapNotNull { packageForUid(it) }
            .distinct()

        // Multiple different apps playing → use Global Visualizer
        if (distinctApps.size > 1) {
            log("🎯 primarySessionId()=0 (multi-app: $distinctApps) → Global Visualizer")
            return 0
        }

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

    /**
     * @param attachedSession session id the Visualizer is currently attached to, so identity can
     *   be resolved from the session we actually consume rather than from the momentary
     *   active-playback snapshot (which lags the first audible frame by 100-300ms).
     */
    fun isBlocked(whitelistMode: Boolean, scopeApps: Set<String>, attachedSession: Int = 0): Boolean {
        // 1) Strongest signal: identity of the session we are actually capturing.
        val sessionPkg = packageForSession(attachedSession)
        val pkgs = when {
            sessionPkg != null -> {
                log("🔒 Resolved identity from session $attachedSession -> $sessionPkg")
                listOf(sessionPkg)
            }
            else -> currentPackages()
        }

        // 2) Nothing identified yet. An empty list right at playback start is normal, not a
        // licence to vibrate — reuse the previous verdict so a sampling gap cannot leak the
        // first beat past the whitelist/blacklist.
        if (pkgs.isEmpty()) {
            return lastBlocked
        }

        val blocked = if (whitelistMode) {
            pkgs.none { it in scopeApps }
        } else {
            pkgs.all { it in scopeApps }
        }
        lastBlocked = blocked
        lastBlockedAtMs = SystemClock.elapsedRealtime()
        return blocked
    }

    /**
     * Active packages, held for a grace period after they disappear.
     * A player dropping out of the active list for one sample (state transition, config
     * re-registration) must not immediately flip the verdict to "nothing is playing".
     */
    private fun currentPackages(): List<String> {
        val now = SystemClock.elapsedRealtime()
        val fresh = mergedActiveUids
            .filterNot { isSystemUid(it) }
            .mapNotNull { packageForUid(it) }
            .distinct()

        if (fresh.isNotEmpty()) {
            lastKnownPkgs = fresh
            lastKnownPkgsAtMs = now
            return fresh
        }

        return if (now - lastKnownPkgsAtMs < PKG_GRACE_MS) {
            log("⏳ No active configs — reusing ${lastKnownPkgs} within ${PKG_GRACE_MS}ms grace")
            lastKnownPkgs
        } else {
            emptyList()
        }
    }

    fun primaryApp(): String? {
        for (uid in mergedActiveUids) {
            if (isSystemUid(uid)) continue
            val pkg = packageForUid(uid)
            if (pkg != null) return pkg
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

    private fun isSystemUid(uid: Int): Boolean =
        uid == Process.SYSTEM_UID ||
            (packageForUid(uid)?.let { it.contains("systemui", ignoreCase = true) || it == RhythmicConstants.SYSTEMUI_PACKAGE } ?: false)

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
        /** getActivePlaybackConfigurations() is a binder call; 200ms keeps the identity window
         *  small enough to beat the first audible beat without hammering audio service. */
        const val REFRESH_INTERVAL_MS = 200L
        /** How long a package stays "active" after it drops out of the active playback list.
         *  Covers the AudioTrack start/stop state transitions and config re-registration gaps. */
        const val PKG_GRACE_MS = 1200L
        /** How long a learned session->package binding stays usable. */
        const val SESSION_GRACE_MS = 3000L
        /** Synthetic session id used for AAudio streams, which report sessionId -1. */
        const val AAUDIO_SESSION = -9999
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