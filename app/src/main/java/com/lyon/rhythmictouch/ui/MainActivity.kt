package com.lyon.rhythmictouch.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lyon.rhythmictouch.BuildConfig
import com.lyon.rhythmictouch.R
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.ConfigStore
import com.lyon.rhythmictouch.config.DeviceConfigStore
import com.lyon.rhythmictouch.config.LocaleHelper
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Settings

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LocaleHelper.getSavedLanguage(newBase)
        if (lang == LocaleHelper.FOLLOW_SYSTEM) {
            super.attachBaseContext(newBase)
            return
        }
        val locale = when (lang) {
            LocaleHelper.CHINESE -> java.util.Locale.CHINESE
            LocaleHelper.ENGLISH -> java.util.Locale.ENGLISH
            else -> java.util.Locale.getDefault()
        }
        val config = android.content.res.Configuration(newBase.resources.configuration)
        config.setLocale(locale)
        val wrapped = newBase.createConfigurationContext(config)
        super.attachBaseContext(wrapped)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val store = remember { ConfigStore(applicationContext) }
            var monetEnabled by rememberSaveable { mutableStateOf(store.read().monet) }
            AppTheme(monetEnabled = monetEnabled) {
                AppNav(store = store, onMonetChange = { monetEnabled = it })
            }
        }
    }
}

private enum class Screen {
    Monitor,
    Config,
    Scope,
    Settings,
}

@Composable
private fun AppNav(
    store: ConfigStore,
    onMonetChange: (Boolean) -> Unit,
) {
    var screen by rememberSaveable { mutableStateOf(Screen.Monitor) }
    var showUpdate by rememberSaveable { mutableStateOf(false) }
    var showDeviceSettings by rememberSaveable { mutableStateOf(false) }
    var hookVersionMismatch by rememberSaveable { mutableStateOf(false) }

    val appContext = LocalContext.current.applicationContext

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppListCache.load(appContext)
        }
        val result = UpdateChecker.fetchLatest()
        if (result != null && UpdateChecker.isNewer(result.latestVersion)) {
            showUpdate = true
        }
        hookVersionMismatch = checkHookVersionMismatch(appContext)
    }

    BackHandler(enabled = screen != Screen.Monitor && !showDeviceSettings && !showUpdate) {
        screen = Screen.Monitor
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appContext.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_START))
                Lifecycle.Event.ON_STOP -> appContext.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_STOP))
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        appContext.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_START))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            appContext.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_STOP))
        }
    }

    if (showUpdate) {
        UpdateScreen(
            onBack = { showUpdate = false },
            onDismiss = { showUpdate = false },
        )
        return
    }

    if (showDeviceSettings) {
        val deviceStore = remember { DeviceConfigStore(appContext) }
        val config = remember { mutableStateOf(store.read()) }
        DeviceConfigListScreen(
            store = deviceStore,
            globalIntensity = config.value.intensity,
            globalDelay = config.value.vibrationDelay,
            onBack = { showDeviceSettings = false },
        )
        return
    }

    when (screen) {
        Screen.Monitor -> MonitorScreen(
            hookVersionMismatch = hookVersionMismatch,
            bottomBar = { MiuixBottomBar(selected = Screen.Monitor) { screen = it } },
        )

        Screen.Config -> ConfigScreen(
            store = store,
            bottomBar = { MiuixBottomBar(selected = Screen.Config) { screen = it } },
        )

        Screen.Scope -> AppListScreen(
            store = store,
            bottomBar = { MiuixBottomBar(selected = Screen.Scope) { screen = it } },
        )

        Screen.Settings -> SettingsScreen(
            store = store,
            onMonetChange = onMonetChange,
            onDeviceSettings = { showDeviceSettings = true },
            bottomBar = { MiuixBottomBar(selected = Screen.Settings) { screen = it } },
        )
    }
}

private fun checkHookVersionMismatch(context: android.content.Context): Boolean {
    return try {
        val result = context.contentResolver.call(
            RhythmicConstants.PROVIDER_URI,
            RhythmicConstants.METHOD_GET_MODULE_VERSION,
            null,
            null,
        )
        val hookVersion = result?.getInt(RhythmicConstants.KEY_MODULE_VERSION, 0) ?: 0
        hookVersion != 0 && hookVersion != BuildConfig.VERSION_CODE
    } catch (_: Throwable) {
        false
    }
}

@Composable
private fun MiuixBottomBar(
    selected: Screen,
    onSelect: (Screen) -> Unit,
) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == Screen.Monitor,
            onClick = { onSelect(Screen.Monitor) },
            icon = MiuixIcons.Music,
            label = stringResource(R.string.nav_monitor),
        )
        NavigationBarItem(
            selected = selected == Screen.Config,
            onClick = { onSelect(Screen.Config) },
            icon = MiuixIcons.File,
            label = stringResource(R.string.nav_config),
        )
        NavigationBarItem(
            selected = selected == Screen.Scope,
            onClick = { onSelect(Screen.Scope) },
            icon = MiuixIcons.GridView,
            label = stringResource(R.string.nav_scope),
        )
        NavigationBarItem(
            selected = selected == Screen.Settings,
            onClick = { onSelect(Screen.Settings) },
            icon = MiuixIcons.Settings,
            label = stringResource(R.string.nav_settings),
        )
    }
}
