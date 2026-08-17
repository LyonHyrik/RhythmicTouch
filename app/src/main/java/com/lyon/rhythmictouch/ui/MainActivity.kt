package com.lyon.rhythmictouch.ui

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.ConfigStore
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.File
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Settings

class MainActivity : ComponentActivity() {

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

    val appContext = LocalContext.current.applicationContext

    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            AppListCache.load(appContext)
        }
        val result = UpdateChecker.fetchLatest()
        if (result != null && UpdateChecker.isNewer(result.latestVersion)) {
            showUpdate = true
        }
    }

    BackHandler(enabled = screen != Screen.Monitor) {
        screen = Screen.Monitor
    }

    val context = LocalContext.current.applicationContext
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> context.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_START))
                Lifecycle.Event.ON_STOP -> context.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_STOP))
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        context.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_START))
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            context.sendBroadcast(Intent(RhythmicConstants.ACTION_OBSERVE_STOP))
        }
    }

    if (showUpdate) {
        UpdateScreen(
            onBack = { showUpdate = false },
            onDismiss = { showUpdate = false },
        )
        return
    }

    when (screen) {
        Screen.Monitor -> MonitorScreen(
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
            bottomBar = { MiuixBottomBar(selected = Screen.Settings) { screen = it } },
        )
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
            label = "监控",
        )
        NavigationBarItem(
            selected = selected == Screen.Config,
            onClick = { onSelect(Screen.Config) },
            icon = MiuixIcons.File,
            label = "配置",
        )
        NavigationBarItem(
            selected = selected == Screen.Scope,
            onClick = { onSelect(Screen.Scope) },
            icon = MiuixIcons.GridView,
            label = "作用域",
        )
        NavigationBarItem(
            selected = selected == Screen.Settings,
            onClick = { onSelect(Screen.Settings) },
            icon = MiuixIcons.Settings,
            label = "设置",
        )
    }
}
