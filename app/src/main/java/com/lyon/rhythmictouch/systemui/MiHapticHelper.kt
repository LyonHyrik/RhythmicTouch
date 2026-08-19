package com.lyon.rhythmictouch.systemui

object MiHapticHelper {

    private const val TAG = "RhythmicMiHaptic"

    private var available: Boolean? = null

    private var dynamicEffectClass: Class<*>? = null
    private var hapticPlayerClass: Class<*>? = null

    // Cached methods
    private var startComposeMethod: java.lang.reflect.Method? = null
    private var createTransientMethod: java.lang.reflect.Method? = null
    private var addPrimitiveMethod: java.lang.reflect.Method? = null
    private var hapticPlayerConstructor: java.lang.reflect.Constructor<*>? = null
    private var hapticPlayerStartMethod: java.lang.reflect.Method? = null
    private var setDataSourceMethod: java.lang.reflect.Method? = null
    private var stopMethod: java.lang.reflect.Method? = null
    private var isAvailableMethod: java.lang.reflect.Method? = null

    // Which constructor/start pattern works
    private var useEffectArgConstructor = false
    private var startMethodTakesEffect = false
    private var startMethodTakesInt = false

    private var cachedPlayer: Any? = null
    private var failureCount = 0

    fun isAvailable(): Boolean {
        if (available != null) return available!!
        android.util.Log.d(TAG, "isAvailable() called, initializing...")
        available = initAll()
        android.util.Log.d(TAG, "isAvailable() result=$available")
        return available!!
    }

    private fun dumpClass(name: String) {
        try {
            val cls = Class.forName(name)
            val sb = StringBuilder("=== $name ===")
            sb.append("\nConstructors:")
            cls.constructors.forEach { c ->
                sb.append("\n  (${c.parameterTypes.joinToString { it.simpleName }})")
            }
            sb.append("\nMethods:")
            cls.declaredMethods.forEach { m ->
                sb.append("\n  ${m.returnType.simpleName} ${m.name}(${m.parameterTypes.joinToString { it.simpleName }})")
            }
            android.util.Log.d(TAG, sb.toString())
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "dumpClass($name) failed: ${t.message}")
        }
    }

    private fun initAll(): Boolean {
        android.util.Log.d(TAG, "initAll() START")
        dumpClass("miui.os.DynamicEffect")
        dumpClass("miui.os.HapticPlayer")
        if (!isHyperOS()) {
            android.util.Log.d(TAG, "Not HyperOS, MiHaptic skipped")
            RhythmicLog.x(TAG, "Not HyperOS, MiHaptic skipped")
            return false
        }

        val packageCandidates = listOf(
            "miui.os" to "DynamicEffect" to "HapticPlayer",
            "android.os" to "DynamicEffect" to "HapticPlayer",
        )

        for ((pair, hpName) in packageCandidates) {
            val (pkg, deName) = pair
            android.util.Log.d(TAG, "Trying $pkg.$deName + $pkg.$hpName")
            if (tryInitFromPackage(pkg, deName, hpName)) {
                val msg = "MiHaptic ready from $pkg, constructor=${if (useEffectArgConstructor) "Effect" else "no-arg"}, start=${when {
                    startMethodTakesEffect -> "effect"
                    startMethodTakesInt -> "int"
                    else -> "no-arg"
                }}"
                android.util.Log.d(TAG, msg)
                RhythmicLog.x(TAG, msg)
                return true
            }
        }

        android.util.Log.d(TAG, "MiHaptic not available - no matching package/methods found")
        RhythmicLog.x(TAG, "MiHaptic not available - no matching package/methods found")
        return false
    }

    private fun tryInitFromPackage(pkg: String, deName: String, hpName: String): Boolean {
        return try {
            val deClass = Class.forName("$pkg.$deName")
            val hpClass = Class.forName("$pkg.$hpName")
            android.util.Log.d(TAG, "Found classes: $pkg.$deName, $pkg.$hpName")

            // DynamicEffect methods - find with float primitive params
            val startCompose = deClass.getMethod("startCompose")

            val createTransient = deClass.methods.find {
                it.name == "createTransient" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType &&
                    it.parameterTypes[1] == Float::class.javaPrimitiveType
            }

            val addPrimitive = deClass.methods.find {
                it.name == "addPrimitive" &&
                    it.parameterTypes.size == 2 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType
            }

            if (createTransient == null || addPrimitive == null) {
                android.util.Log.w(TAG, "DynamicEffect methods missing: createTransient=${createTransient != null} addPrimitive=${addPrimitive != null}")
                return false
            }
            android.util.Log.d(TAG, "DynamicEffect methods OK: startCompose, createTransient(float,float), addPrimitive(float,*)")

            // HapticPlayer constructor: try Effect-arg first, then no-arg
            var hpConstructor: java.lang.reflect.Constructor<*>? = null
            var useEffectCtor = false

            // Try HapticPlayer(DynamicEffect)
            hpConstructor = hpClass.constructors.find {
                it.parameterTypes.size == 1 && it.parameterTypes[0] == deClass
            }
            if (hpConstructor != null) {
                useEffectCtor = true
            } else {
                // Try HapticPlayer() no-arg
                hpConstructor = hpClass.constructors.find { it.parameterTypes.isEmpty() }
                useEffectCtor = false
            }

            if (hpConstructor == null) {
                android.util.Log.w(TAG, "HapticPlayer constructors: ${hpClass.constructors.map { "${it.parameterTypes.map { p -> p.simpleName }}" }}")
                return false
            }
            android.util.Log.d(TAG, "HapticPlayer constructor: useEffectArg=$useEffectCtor params=${hpConstructor.parameterTypes.map { it.simpleName }}")

            // HapticPlayer.start: try different signatures
            // Pattern A (docs): constructor(effect), start() no-arg
            // Pattern B (CHOICE_PARALYSIS): constructor(), start(effect)
            // Pattern C (RichTap ref): constructor(effect), start(int loop)

            var startMethod: java.lang.reflect.Method? = null
            var takesEffect = false
            var takesInt = false

            if (useEffectCtor) {
                // Constructor takes effect, try start() no-arg first
                startMethod = hpClass.methods.find {
                    it.name == "start" && it.parameterTypes.isEmpty()
                }
                if (startMethod != null) {
                    takesEffect = false
                    takesInt = false
                } else {
                    // Try start(int loop)
                    startMethod = hpClass.methods.find {
                        it.name == "start" &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType
                    }
                    if (startMethod != null) {
                        takesInt = true
                    }
                }
            } else {
                // No-arg constructor, try start(DynamicEffect)
                startMethod = hpClass.methods.find {
                    it.name == "start" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == deClass
                }
                if (startMethod != null) {
                    takesEffect = true
                } else {
                    // Try start(int) with no-arg ctor (unlikely but possible)
                    startMethod = hpClass.methods.find {
                        it.name == "start" &&
                            it.parameterTypes.size == 1 &&
                            it.parameterTypes[0] == Int::class.javaPrimitiveType
                    }
                    if (startMethod != null) {
                        takesInt = true
                    }
                }
            }

            if (startMethod == null) {
                android.util.Log.w(TAG, "HapticPlayer.start not found. Methods: ${hpClass.methods.filter { it.name == "start" }.map { "${it.parameterTypes.map { p -> p.simpleName }}" }}")
                return false
            }
            android.util.Log.d(TAG, "HapticPlayer.start: takesEffect=$takesEffect takesInt=$takesInt params=${startMethod.parameterTypes.map { it.simpleName }}")

            // Cache optional methods for player reuse
            val setDs = hpClass.methods.find {
                it.name == "setDataSource" &&
                    it.parameterTypes.size == 1 &&
                    it.parameterTypes[0] == deClass
            }
            val stopM = hpClass.methods.find {
                it.name == "stop" && it.parameterTypes.isEmpty()
            }
            val isAvail = hpClass.methods.find {
                it.name == "isAvailable" && it.parameterTypes.isEmpty()
            }
            android.util.Log.d(TAG, "HapticPlayer optional: setDataSource=${setDs != null} stop=${stopM != null} isAvailable=${isAvail != null}")

            // Store everything
            dynamicEffectClass = deClass
            hapticPlayerClass = hpClass
            startComposeMethod = startCompose
            createTransientMethod = createTransient
            addPrimitiveMethod = addPrimitive
            hapticPlayerConstructor = hpConstructor
            hapticPlayerStartMethod = startMethod
            setDataSourceMethod = setDs
            stopMethod = stopM
            isAvailableMethod = isAvail
            useEffectArgConstructor = useEffectCtor
            startMethodTakesEffect = takesEffect
            startMethodTakesInt = takesInt

            true
        } catch (t: Throwable) {
            RhythmicLog.x(TAG, "tryInitFromPackage $pkg failed: ${t.message}")
            false
        }
    }

    fun playTransient(intensity: Float, sharpness: Float, delayMs: Long = 0): Boolean {
        if (available != true) {
            android.util.Log.w(TAG, "playTransient SKIPPED: available=$available")
            return false
        }
        if (failureCount > 10) {
            android.util.Log.w(TAG, "playTransient SKIPPED: failureCount=$failureCount")
            return false
        }

        try {
            val intensityCoerced = intensity.coerceIn(0.01f, 1.0f)
            val sharpnessCoerced = sharpness.coerceIn(0f, 1.0f)

            val effect = startComposeMethod!!.invoke(null)!!
            val primitive = createTransientMethod!!.invoke(null, intensityCoerced, sharpnessCoerced)!!
            addPrimitiveMethod!!.invoke(effect, delayMs / 1000f, primitive)

            val player = getOrCreatePlayer()

            // Stop any ongoing playback before starting new effect
            try {
                stopMethod?.invoke(player)
            } catch (_: Throwable) {}

            // Pattern 1: Use setDataSource + start(effect) if available
            if (setDataSourceMethod != null && startMethodTakesEffect) {
                setDataSourceMethod!!.invoke(player, effect)
                hapticPlayerStartMethod!!.invoke(player, effect)
            }
            // Pattern 2: Use setDataSource + start() no-arg
            else if (setDataSourceMethod != null) {
                setDataSourceMethod!!.invoke(player, effect)
                hapticPlayerStartMethod!!.invoke(player)
            }
            // Pattern 3: Create new player with effect each time (original behavior)
            else if (useEffectArgConstructor) {
                val freshPlayer = hapticPlayerConstructor!!.newInstance(effect)
                when {
                    startMethodTakesEffect -> hapticPlayerStartMethod!!.invoke(freshPlayer, effect)
                    startMethodTakesInt -> hapticPlayerStartMethod!!.invoke(freshPlayer, 1)
                    else -> hapticPlayerStartMethod!!.invoke(freshPlayer)
                }
            }
            // Pattern 4: Direct start with effect
            else {
                when {
                    startMethodTakesEffect -> hapticPlayerStartMethod!!.invoke(player, effect)
                    startMethodTakesInt -> hapticPlayerStartMethod!!.invoke(player, 1)
                    else -> hapticPlayerStartMethod!!.invoke(player)
                }
            }

            android.util.Log.d(TAG, "playTransient OK: intensity=${intensityCoerced} sharpness=${sharpnessCoerced} delay=${delayMs}ms")
            failureCount = 0
            return true
        } catch (t: Throwable) {
            failureCount++
            android.util.Log.e(TAG, "playTransient FAILED (#$failureCount): ${t.message}", t)
            if (failureCount >= 5) {
                resetCache()
                available = null
            }
            return false
        }
    }

    private fun resetCache() {
        resetPlayerCache()
        dynamicEffectClass = null
        hapticPlayerClass = null
        startComposeMethod = null
        createTransientMethod = null
        addPrimitiveMethod = null
        hapticPlayerConstructor = null
        hapticPlayerStartMethod = null
        setDataSourceMethod = null
        stopMethod = null
        isAvailableMethod = null
    }

    private fun resetPlayerCache() {
        if (cachedPlayer != null) {
            try { stopMethod?.invoke(cachedPlayer) } catch (_: Throwable) {}
        }
        cachedPlayer = null
    }

    @Synchronized
    private fun getOrCreatePlayer(): Any {
        if (cachedPlayer != null) return cachedPlayer!!

        val player = if (useEffectArgConstructor) {
            // Create with empty DynamicEffect initially, will be updated via setDataSource
            val initialEffect = startComposeMethod!!.invoke(null)
            hapticPlayerConstructor!!.newInstance(initialEffect)
        } else {
            hapticPlayerConstructor!!.newInstance()
        }

        // Check if the player is available
        val playerAvailable = try {
            isAvailableMethod?.invoke(player) as? Boolean ?: true
        } catch (_: Throwable) { true }

        android.util.Log.d(TAG, "getOrCreatePlayer: new player created, isAvailable=$playerAvailable")
        cachedPlayer = player
        return player
    }

    fun getSharpnessForMode(modeKey: String): Float = when (modeKey) {
        "heavyShort" -> 0.9f
        "mediumHit" -> 0.7f
        "midTap" -> 0.6f
        "risingTap" -> 0.5f
        "softTick" -> 0.3f
        else -> 0.5f
    }

    fun getIntensityMultiplier(modeKey: String): Float = when (modeKey) {
        "softTick" -> 0.5f
        "risingTap" -> 0.8f
        else -> 1.0f
    }

    private fun isHyperOS(): Boolean {
        return try {
            val miuiVersion = getSystemProperty("ro.miui.ui.version.name")
            val miOsVersion = getSystemProperty("ro.mi.os.version.incremental")
            val displayId = getSystemProperty("ro.build.display.id")
            miOsVersion.isNotEmpty() || miuiVersion.isNotEmpty() || displayId.contains("OS", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun getSystemProperty(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val get = clazz.getMethod("get", String::class.java, String::class.java)
            get.invoke(null, key, "") as? String ?: ""
        } catch (_: Throwable) {
            ""
        }
    }
}
