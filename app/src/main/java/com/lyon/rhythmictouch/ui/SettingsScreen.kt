package com.lyon.rhythmictouch.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.lyon.rhythmictouch.R
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.ConfigStore
import com.lyon.rhythmictouch.config.LocaleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun SettingsScreen(
    store: ConfigStore,
    onMonetChange: (Boolean) -> Unit,
    onDeviceSettings: () -> Unit = {},
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val config = remember { mutableStateOf(store.read()) }
    var isRestarting by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var showQuietPeriods by remember { mutableStateOf(false) }
    var syncedInterval by remember { mutableStateOf<Int?>(null) }
    var vibratorCalMinMs by remember { mutableStateOf(50L) }
    var showLanguageDialog by remember { mutableStateOf(false) }
    var currentLanguage by remember { mutableStateOf(LocaleHelper.getSavedLanguage(context)) }
    val activity = LocalContext.current as? android.app.Activity

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("vibrator_cal", android.content.Context.MODE_PRIVATE)
                val cached = prefs.getLong("effectiveMinIntervalMs", 0L)
                if (cached > 0) {
                    vibratorCalMinMs = cached
                } else {
                    val vibrator = context.getSystemService(android.os.Vibrator::class.java)
                    if (vibrator != null && vibrator.hasVibrator()) {
                        val testIntervals = longArrayOf(20, 25, 30, 35, 40, 45, 50, 60, 70, 80)
                        var result = 50L
                        for (interval in testIntervals) {
                            val count = 10
                            val times = mutableListOf<Long>()
                            val latch = java.util.concurrent.CountDownLatch(count)
                            val handler = Handler(android.os.Looper.getMainLooper())
                            for (i in 0 until count) {
                                handler.postDelayed({
                                    try { vibrator.vibrate(android.os.VibrationEffect.createOneShot(5, 80)) } catch (_: Exception) {}
                                    synchronized(times) { times.add(android.os.SystemClock.elapsedRealtime()) }
                                    latch.countDown()
                                }, i * interval.toLong())
                            }
                            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
                            if (times.size < 3) continue
                            val actual = times.zipWithNext().map { (a, b) -> b - a }
                            val avg = actual.average().toLong()
                            val max = actual.maxOrNull() ?: 0L
                            if (avg <= interval * 1.3 && max <= interval * 2.0) {
                                result = interval
                            } else {
                                break
                            }
                            Thread.sleep(100)
                        }
                        vibratorCalMinMs = result
                        prefs.edit().putLong("effectiveMinIntervalMs", result).putLong("timestamp", System.currentTimeMillis()).apply()
                    }
                }
            } catch (_: Throwable) {}
            if (config.value.aaudioIntervalMs < vibratorCalMinMs.toInt()) {
                config.value = config.value.copy(aaudioIntervalMs = vibratorCalMinMs.toInt())
                store.write(config.value)
            }
        }
    }

    DisposableEffect(config.value.syncAaudioWithAudioTrack) {
        if (config.value.syncAaudioWithAudioTrack) {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    val interval = intent?.getIntExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, 0) ?: 0
                    if (interval > 0) {
                        syncedInterval = interval
                    }
                }
            }
            val filter = IntentFilter(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.registerReceiver(receiver, filter)
            }
            context.sendBroadcast(Intent(RhythmicConstants.ACTION_REQUEST_DETECTED_INTERVAL))
            onDispose {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) {}
            }
        } else {
            syncedInterval = null
            onDispose {}
        }
    }

    if (showAbout) {
        AboutScreen(
            onBack = { showAbout = false },
            monetEnabled = config.value.monet,
        )
        return
    }

    if (showQuietPeriods) {
        QuietPeriodScreen(
            store = store,
            onBack = { showQuietPeriods = false },
        )
        return
    }

    suspend fun restartSystemUI() {
        isRestarting = true
        try {
            withContext(Dispatchers.IO) {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "am crash com.android.systemui"))
                process.waitFor()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            kotlinx.coroutines.delay(2000)
            isRestarting = false
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(title = stringResource(R.string.screen_settings))
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SuperSwitch(
                checked = config.value.enabled,
                onCheckedChange = { checked ->
                    config.value = config.value.copy(enabled = checked)
                    store.write(config.value)
                    context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                },
                title = stringResource(R.string.setting_rhythmic_touch),
                summary = stringResource(R.string.setting_rhythmic_touch_desc),
            )

            SuperSwitch(
                checked = config.value.whitelistMode,
                onCheckedChange = { checked ->
                    config.value = config.value.copy(whitelistMode = checked)
                    store.write(config.value)
                    context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                },
                title = stringResource(R.string.setting_whitelist_mode),
                summary = if (config.value.whitelistMode) {
                    stringResource(R.string.setting_whitelist_on)
                } else {
                    stringResource(R.string.setting_whitelist_off)
                },
            )

            SuperSwitch(
                checked = config.value.monet,
                onCheckedChange = { checked ->
                    config.value = config.value.copy(monet = checked)
                    store.write(config.value)
                    onMonetChange(checked)
                },
                title = stringResource(R.string.setting_monet),
                summary = stringResource(R.string.setting_monet_desc),
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_vibration_intensity),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = "${config.value.intensity}%",
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Slider(
                        value = config.value.intensity.toFloat(),
                        onValueChange = { value ->
                            config.value = config.value.copy(intensity = value.toInt())
                            store.write(config.value)
                        },
                        valueRange = 0f..100f,
                        onValueChangeFinished = {
                            context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                        },
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_vibration_delay),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = "${config.value.vibrationDelay}ms",
                            color = MiuixTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = stringResource(R.string.setting_delay_desc),
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 12.sp,
                    )
                    Slider(
                        value = config.value.vibrationDelay.toFloat(),
                        onValueChange = { value ->
                            config.value = config.value.copy(vibrationDelay = value.toInt())
                            store.write(config.value)
                        },
                        valueRange = 0f..300f,
                        onValueChangeFinished = {
                            context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                        },
                    )
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDeviceSettings() }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_device_config),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = stringResource(R.string.setting_device_config_desc),
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }

            SmallTitle(stringResource(R.string.section_audio_sync))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.setting_aaudio_interval),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = if (config.value.syncAaudioWithAudioTrack) {
                                syncedInterval?.let { "${it}ms (${stringResource(R.string.label_auto)})" } ?: stringResource(R.string.label_waiting_detect)
                            } else {
                                "${config.value.aaudioIntervalMs}ms"
                            },
                            color = if (config.value.syncAaudioWithAudioTrack) MiuixTheme.colorScheme.onSurfaceContainerVariant else MiuixTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        text = if (config.value.syncAaudioWithAudioTrack) {
                            stringResource(R.string.setting_sync_auto_desc)
                        } else {
                            stringResource(R.string.setting_sync_manual_desc)
                        },
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 12.sp,
                    )
                    Slider(
                        enabled = !config.value.syncAaudioWithAudioTrack,
                        value = (syncedInterval ?: config.value.aaudioIntervalMs).toFloat(),
                        onValueChange = { value ->
                            if (!config.value.syncAaudioWithAudioTrack) {
                                config.value = config.value.copy(aaudioIntervalMs = value.toInt())
                                store.write(config.value)
                            }
                        },
                        valueRange = vibratorCalMinMs.toFloat()..300f,
                        onValueChangeFinished = {
                            if (!config.value.syncAaudioWithAudioTrack) {
                                context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG).apply {
                                    putExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, config.value.aaudioIntervalMs)
                                    putExtra(RhythmicConstants.EXTRA_SYNC_ENABLED, false)
                                })
                                context.sendBroadcast(Intent(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL).apply {
                                    putExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, config.value.aaudioIntervalMs)
                                })
                            }
                        },
                    )
                }
            }

            SuperSwitch(
                checked = config.value.syncAaudioWithAudioTrack,
                onCheckedChange = { checked ->
                    config.value = config.value.copy(syncAaudioWithAudioTrack = checked)
                    store.write(config.value)
                    if (checked) {
                        syncedInterval = null
                    }
                    context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG).apply {
                        putExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, config.value.aaudioIntervalMs)
                        putExtra(RhythmicConstants.EXTRA_SYNC_ENABLED, checked)
                    })
                    if (!checked) {
                        context.sendBroadcast(Intent(RhythmicConstants.ACTION_SYNC_AAUDIO_INTERVAL).apply {
                            putExtra(RhythmicConstants.EXTRA_AAUDIO_INTERVAL_MS, config.value.aaudioIntervalMs)
                        })
                    }
                },
                title = stringResource(R.string.setting_auto_sync),
                summary = stringResource(R.string.setting_auto_sync_desc),
            )

            SmallTitle(stringResource(R.string.section_log))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.setting_log_mode),
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                    )
                    Text(
                        text = when (config.value.logMode) {
                            RhythmicConstants.LOG_MODE_VIBRATE -> stringResource(R.string.log_mode_vibrate_desc)
                            RhythmicConstants.LOG_MODE_NONE -> stringResource(R.string.log_mode_none_desc)
                            else -> stringResource(R.string.log_mode_all_desc)
                        },
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        val options = listOf(
                            RhythmicConstants.LOG_MODE_ALL to stringResource(R.string.log_mode_all),
                            RhythmicConstants.LOG_MODE_VIBRATE to stringResource(R.string.log_mode_vibrate),
                            RhythmicConstants.LOG_MODE_NONE to stringResource(R.string.log_mode_none),
                        )
                        options.forEach { (mode, label) ->
                            val selected = config.value.logMode == mode
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (selected) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceContainerHighest
                                    )
                                    .clickable {
                                        config.value = config.value.copy(logMode = mode)
                                        store.write(config.value)
                                        context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                                    }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = label,
                                    color = if (selected) Color.White
                                    else MiuixTheme.colorScheme.onSurfaceContainer,
                                    fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }
            }

            SmallTitle(stringResource(R.string.section_system))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLanguageDialog = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_language),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = LocaleHelper.getLanguageDisplayName(currentLanguage),
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isRestarting) {
                            GlobalScope.launch { restartSystemUI() }
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_restart_systemui),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = if (isRestarting) stringResource(R.string.setting_restart_restarting) else stringResource(R.string.setting_restart_desc),
                            color = if (isRestarting) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showQuietPeriods = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_quiet_periods),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = if (config.value.quietPeriods.isEmpty()) stringResource(R.string.quiet_not_set)
                            else stringResource(R.string.quiet_active_count, config.value.quietPeriods.count { it.enabled }),
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAbout = true }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.setting_about),
                            color = MiuixTheme.colorScheme.onSurfaceContainer,
                        )
                        Text(
                            text = stringResource(R.string.app_name),
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }
        }

        if (showLanguageDialog) {
            val langs = listOf(
                LocaleHelper.FOLLOW_SYSTEM to stringResource(R.string.language_system),
                LocaleHelper.CHINESE to "中文",
                LocaleHelper.ENGLISH to "English",
            )
            top.yukonga.miuix.kmp.basic.Scaffold { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(Color.Black.copy(alpha = 0.4f))
                        .clickable(
                            indication = null,
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        ) { showLanguageDialog = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Card(
                        modifier = Modifier.padding(horizontal = 32.dp),
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Text(
                                text = stringResource(R.string.setting_language),
                                color = MiuixTheme.colorScheme.onSurfaceContainer,
                                fontSize = 17.sp,
                                modifier = Modifier.padding(bottom = 12.dp),
                            )
                            langs.forEach { (key, label) ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (currentLanguage == key) MiuixTheme.colorScheme.primaryContainer
                                            else Color.Transparent,
                                        )
                                        .clickable {
                                            LocaleHelper.saveLanguage(context, key)
                                            currentLanguage = key
                                            showLanguageDialog = false
                                            activity?.recreate()
                                        }
                                        .padding(vertical = 12.dp, horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = label,
                                        color = if (currentLanguage == key) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.onSurfaceContainer,
                                        fontSize = 15.sp,
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}