package com.lyon.rhythmictouch.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.res.stringResource
import com.lyon.rhythmictouch.R
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.extra.SuperSwitch
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Import
import androidx.compose.ui.graphics.graphicsLayer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppEntry(
    val label: String,
    val pkg: String,
    val system: Boolean,
)

object AppListCache {
    @Volatile
    var apps: List<AppEntry>? = null
        private set

    fun load(context: Context) {
        try {
            apps = loadApps(context)
        } catch (_: Throwable) {}
    }
}

private val iconCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()

@Composable
fun AppListScreen(
    store: ConfigStore,
    onBack: (() -> Unit)? = null,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val config = remember { mutableStateOf(store.read()) }
    val query = remember { TextFieldState() }
    var tab by remember { mutableIntStateOf(0) }
    var apps by remember { mutableStateOf(AppListCache.apps ?: emptyList()) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (AppListCache.apps == null) {
            withContext(Dispatchers.IO) { AppListCache.load(context) }
            apps = AppListCache.apps ?: emptyList()
        }
    }

    val userApps = remember(apps) { apps.filter { !it.system } }
    val systemApps = remember(apps) { apps.filter { it.system } }
    val tabApps = if (tab == 0) userApps else systemApps

    val queryText = query.text.toString()
    val filtered = remember(tabApps, queryText) {
        val q = queryText.trim()
        if (q.isEmpty()) {
            tabApps
        } else {
            tabApps.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            val cfg = config.value
            val json = JSONObject().apply {
                put("whitelistMode", cfg.whitelistMode)
                put("excludedApps", JSONArray(cfg.excludedApps.toList()))
            }
            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val ok = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(bytes)
                    true
                } ?: false
            } catch (_: Throwable) {
                false
            }
            Toast.makeText(
                context,
                if (ok) context.getString(R.string.toast_export_scope) else context.getString(R.string.toast_export_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Throwable) {
                null
            }
            if (bytes == null) {
                Toast.makeText(context, context.getString(R.string.toast_read_file_failed), Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
            }
            try {
                val json = JSONObject(String(bytes, Charsets.UTF_8))
                val whitelist = json.optBoolean("whitelistMode", config.value.whitelistMode)
                val list = json.optJSONArray("excludedApps")
                val apps = if (list != null) {
                    buildSet {
                        for (i in 0 until list.length()) add(list.optString(i))
                    }
                } else {
                    config.value.excludedApps
                }
                config.value = config.value.copy(whitelistMode = whitelist, excludedApps = apps)
                store.write(config.value)
                context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                Toast.makeText(context, context.getString(R.string.toast_import_scope), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Toast.makeText(context, context.getString(R.string.toast_import_scope_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.screen_scope),
                navigationIcon = {
                    onBack?.let { back ->
                        IconButton(onClick = back) {
                            Icon(
                                imageVector = MiuixIcons.Basic.ArrowRight,
                                contentDescription = stringResource(R.string.action_back),
                                modifier = Modifier.graphicsLayer { scaleX = -1f },
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(MiuixIcons.Import, contentDescription = stringResource(R.string.action_import))
                    }
                    IconButton(onClick = {
                        exportLauncher.launch("RhythmicTouch_scope_$stamp.json")
                    }) {
                        Icon(MiuixIcons.Backup, contentDescription = stringResource(R.string.action_export))
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            top.yukonga.miuix.kmp.basic.Text(
                text = if (config.value.whitelistMode) {
                    stringResource(R.string.scope_whitelist_hint)
                } else {
                    stringResource(R.string.scope_blacklist_hint)
                },
                color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackgroundVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            TextField(
                state = query,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = stringResource(R.string.label_search_apps),
                useLabelAsPlaceholder = true,
                leadingIcon = {
                    Icon(
                        imageVector = MiuixIcons.Basic.Search,
                        contentDescription = null,
                    )
                },
            )

            TabRow(
                listOf(stringResource(R.string.tab_user_apps, userApps.size), stringResource(R.string.tab_system_apps, systemApps.size)),
                tab,
                { tab = it },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            top.yukonga.miuix.kmp.basic.PullToRefresh(
                isRefreshing = refreshing,
                onRefresh = {
                    refreshing = true
                    GlobalScope.launch {
                        withContext(Dispatchers.IO) { AppListCache.load(context) }
                        apps = AppListCache.apps ?: emptyList()
                        kotlinx.coroutines.delay(300)
                        refreshing = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (filtered.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                top.yukonga.miuix.kmp.basic.Text(
                                    text = stringResource(R.string.hint_no_matching_apps),
                                    color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onBackgroundVariant,
                                )
                            }
                        }
                    }
                    items(filtered, key = { it.pkg }) { entry ->
                        SuperSwitch(
                            modifier = Modifier.fillMaxWidth(),
                            checked = entry.pkg in config.value.excludedApps,
                            onCheckedChange = { checked ->
                                val set = config.value.excludedApps.toMutableSet()
                                if (checked) set += entry.pkg else set -= entry.pkg
                                config.value = config.value.copy(excludedApps = set)
                                store.write(config.value)
                                context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                            },
                            title = entry.label,
                            summary = entry.pkg,
                            startAction = {
                                AppIcon(context, entry.pkg)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AppIcon(context: android.content.Context, pkg: String) {
    var icon by remember(pkg) { mutableStateOf(iconCache[pkg]) }
    LaunchedEffect(pkg) {
        if (icon == null) {
            val loaded = withContext(Dispatchers.IO) {
                iconCache[pkg] ?: try {
                    val pm = context.packageManager
                    val ai = pm.getApplicationInfo(pkg, 0)
                    val bmp = ai.loadIcon(pm).toBitmap(96, 96).asImageBitmap()
                    iconCache[pkg] = bmp
                    bmp
                } catch (t: Throwable) {
                    null
                }
            }
            if (loaded != null) icon = loaded
        }
    }
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(androidx.compose.ui.graphics.Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon!!,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
            )
        }
    }
}

private fun loadApps(context: Context): List<AppEntry> {
    val pm = context.packageManager
    val entries = mutableListOf<AppEntry>()
    try {
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
        for (ai in infos) {
            val pkg = ai.packageName
            if (pkg == RhythmicConstants.SYSTEMUI_PACKAGE || pkg == RhythmicConstants.MODULE_PACKAGE) continue
            val label = try {
                pm.getApplicationLabel(ai)?.toString() ?: pkg
            } catch (t: Throwable) {
                pkg
            }
            entries += AppEntry(label, pkg, isSystemApp(ai))
        }
    } catch (t: Throwable) {
        // Fall back to whatever we managed to collect.
    }
    entries.sortWith(compareBy<AppEntry> { it.system }.thenBy { it.label.lowercase() })
    return entries
}

private fun isSystemApp(ai: ApplicationInfo): Boolean =
    (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
