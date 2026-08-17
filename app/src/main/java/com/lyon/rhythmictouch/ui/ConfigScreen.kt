package com.lyon.rhythmictouch.ui

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.ConfigStore
import com.lyon.rhythmictouch.config.ProfileExporter
import com.lyon.rhythmictouch.config.ProfileStore
import com.lyon.rhythmictouch.config.VibrationParams
import com.lyon.rhythmictouch.config.VibrationProfile
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Alarm
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.RecordingTape
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.icon.extended.VolumeUp
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun ConfigScreen(
    store: ConfigStore,
    bottomBar: @Composable () -> Unit = {},
) {
    val context = LocalContext.current.applicationContext
    val profileStore = remember { ProfileStore(context) }
    var profiles by remember { mutableStateOf(profileStore.readProfiles()) }
    var activeId by remember { mutableStateOf(profileStore.readActiveId()) }
    var editing by remember { mutableStateOf<VibrationProfile?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    var exportTarget by remember { mutableStateOf<VibrationProfile?>(null) }
    var pendingZipBytes by remember { mutableStateOf<ByteArray?>(null) }

    fun reload() {
        profiles = profileStore.readProfiles()
        activeId = profileStore.readActiveId()
    }

    fun select(id: String) {
        profileStore.setActive(id)
        activeId = id
        context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
    }

    val exportSingleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        val target = exportTarget
        if (uri != null && target != null) {
            val bytes = ProfileExporter.singleJsonBytes(target)
            if (bytes != null) {
                val ok = ProfileExporter.writeToUri(context, uri, bytes)
                Toast.makeText(
                    context,
                    if (ok) "已导出 ${target.name}.json" else "导出失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val exportZipLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        val bytes = pendingZipBytes
        if (uri != null && bytes != null) {
            val ok = ProfileExporter.writeToUri(context, uri, bytes)
            Toast.makeText(
                context,
                if (ok) "已导出配置包 zip" else "导出失败",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        var count = 0
        for (uri in uris) {
            val bytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (_: Throwable) {
                null
            } ?: continue
            val imported = ProfileExporter.importFromBytes(bytes)
            for (p in imported) {
                profileStore.importProfile(p)
                count++
            }
        }
        reload()
        Toast.makeText(
            context,
            if (count > 0) "导入成功 $count 个配置" else "未找到可导入的配置",
            Toast.LENGTH_SHORT,
        ).show()
    }

    val currentEdit = editing
    if (currentEdit != null) {
        ConfigEditorScreen(
            profile = currentEdit,
            profileStore = profileStore,
            isNew = editingIsNew,
            readOnly = currentEdit.isDefault,
            onBack = {
                editing = null
                reload()
            },
        )
        return
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = "配置",
                actions = {
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(MiuixIcons.Import, contentDescription = "导入")
                    }
                    IconButton(onClick = {
                        val bytes = ProfileExporter.batchZipBytes(profiles)
                        if (bytes == null) {
                            Toast.makeText(context, "没有可导出的自定义配置", Toast.LENGTH_SHORT).show()
                        } else {
                            pendingZipBytes = bytes
                            exportZipLauncher.launch(ProfileExporter.suggestedBatchFileName())
                        }
                    }) {
                        Icon(MiuixIcons.Backup, contentDescription = "批量导出")
                    }
                    IconButton(onClick = {
                        editingIsNew = true
                        editing = profileStore.createDraft()
                    }) {
                        Icon(MiuixIcons.Add, contentDescription = "新建配置")
                    }
                },
            )
        },
        bottomBar = bottomBar,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SmallTitle("配置文件")

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column {
                    profiles.forEach { p ->
                        ProfileRow(
                            profile = p,
                            isActive = p.id == activeId,
                            onSelect = { select(p.id) },
                            onLongPress = {
                                editingIsNew = false
                                editing = p
                            },
                            onExport = {
                                exportTarget = p
                                exportSingleLauncher.launch("${ProfileExporter.sanitize(p.name)}.json")
                            },
                            onDelete = {
                                profileStore.deleteProfile(p.id)
                                reload()
                                context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                            },
                        )
                    }
                }
            }

            Text(
                text = "点击配置即可切换启用 · 长按进入参数修改",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileRow(
    profile: VibrationProfile,
    isActive: Boolean,
    onSelect: () -> Unit,
    onLongPress: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongPress,
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
        ) {
            Text(
                text = profile.name,
                color = if (isActive) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainer,
            )
            Text(
                text = when {
                    profile.isDefault -> "内置默认 · 长按查看"
                    profile.scopeApps.isNotEmpty() && isActive -> "作用域 ${profile.scopeApps.size} 个应用 · 当前生效"
                    profile.scopeApps.isNotEmpty() -> "作用域 ${profile.scopeApps.size} 个应用 · 长按修改"
                    isActive -> "全局配置 · 当前生效 · 长按修改"
                    else -> "点击启用 · 长按修改"
                },
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            )
        }
        if (isActive) {
            Icon(
                imageVector = MiuixIcons.Basic.Check,
                contentDescription = "当前生效",
                tint = MiuixTheme.colorScheme.primary,
            )
        }
        if (!profile.isDefault) {
            IconButton(onClick = onExport) {
                Icon(MiuixIcons.Download, contentDescription = "导出")
            }
            IconButton(onClick = onDelete) {
                Icon(MiuixIcons.Delete, contentDescription = "删除")
            }
        }
    }
}

@Composable
private fun ConfigEditorScreen(
    profile: VibrationProfile,
    profileStore: ProfileStore,
    isNew: Boolean,
    readOnly: Boolean = false,
    onBack: () -> Unit,
) {
    BackHandler { onBack() }
    val context = LocalContext.current.applicationContext
    var current by remember { mutableStateOf(profile) }
    var name by remember { mutableStateOf(profile.name) }

    fun persist(updated: VibrationProfile) {
        current = updated
        if (readOnly) return
        profileStore.addOrUpdate(updated)
        context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
    }

    fun finish() {
        if (readOnly) return
        val safeName = name.trim().ifEmpty { "未命名配置" }
        persist(current.copy(name = safeName))
        onBack()
    }

    fun cancel() {
        onBack()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = if (readOnly) "${profile.name} (只读)" else if (isNew) "新建配置" else profile.name,
                navigationIcon = {
                    IconButton(onClick = { cancel() }) {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = "返回",
                            modifier = Modifier.graphicsLayer { rotationZ = 180f },
                        )
                    }
                },
                actions = {
                    if (!readOnly) {
                        IconButton(onClick = { finish() }) {
                            Icon(MiuixIcons.Basic.Check, contentDescription = "保存")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = if (readOnly) "此配置为内置默认，仅可查看" else "拖动滑块实时生效，无需重启",
                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )

            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                label = "配置名称",
                enabled = !readOnly,
            )

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column {
                    VibrationParams.MODE_LABELS.forEach { (key, label) ->
                        ModeSliderGroup(
                            icon = modeIcons[key],
                            label = label,
                            amp = current.params.ampOf(key),
                            dur = current.params.durOf(key),
                            bandStart = current.params.bandStartOf(key),
                            bandEnd = current.params.bandEndOf(key),
                            activeBands = current.params.activeBandsOf(key),
                            editable = !readOnly,
                            onAmp = { amp ->
                                persist(current.copy(params = current.params.withAmp(key, amp)))
                            },
                            onDur = { dur ->
                                persist(current.copy(params = current.params.withDur(key, dur)))
                            },
                            onBandRange = { s, e ->
                                persist(current.copy(params = current.params.withBandRange(key, s, e)))
                            },
                            onActiveBands = { bands ->
                                persist(current.copy(params = current.params.withActiveBands(key, bands)))
                            },
                        )
                    }
                }
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("配置文件作用域", color = MiuixTheme.colorScheme.onSurfaceContainer, fontSize = 14.sp)
                    Text(
                        text = if (current.scopeApps.isEmpty()) "全局配置 · 无作用域限制" else "匹配到对应软件时自动激活此配置",
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (readOnly) {
                        if (current.scopeApps.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                current.scopeApps.forEach { pkg ->
                                    val appLabel = remember(pkg) {
                                        try {
                                            context.packageManager.getApplicationLabel(
                                                context.packageManager.getApplicationInfo(pkg, 0)
                                            ).toString()
                                        } catch (_: Throwable) { pkg.substringAfterLast('.') }
                                    }
                                    Row(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(text = appLabel, color = MiuixTheme.colorScheme.onSurfaceContainer, fontSize = 12.sp)
                                    }
                                }
                            }
                        } else {
                            Text("无", color = MiuixTheme.colorScheme.onSurfaceContainerVariant, fontSize = 12.sp)
                        }
                    } else {
                        ScopeAppsEditor(
                            scopeApps = current.scopeApps,
                            onAdd = { pkg -> persist(current.copy(scopeApps = current.scopeApps + pkg)) },
                            onRemove = { pkg -> persist(current.copy(scopeApps = current.scopeApps - pkg)) },
                        )
                    }
                }
            }
        }
    }
}

private val modeIcons: Map<String, ImageVector> = mapOf(
    VibrationParams.KEY_HEAVY_LONG to MiuixIcons.Music,
    VibrationParams.KEY_HEAVY_SHORT to MiuixIcons.Play,
    VibrationParams.KEY_MID_TAP to MiuixIcons.RecordingTape,
    VibrationParams.KEY_MEDIUM_HIT to MiuixIcons.Timer,
    VibrationParams.KEY_RISING_TAP to MiuixIcons.Alarm,
    VibrationParams.KEY_LONG_PULSE to MiuixIcons.VolumeUp,
    VibrationParams.KEY_EMOTION_PULSE to MiuixIcons.Refresh,
    VibrationParams.KEY_SOFT_TICK to MiuixIcons.Tune,
)

@Composable
private fun ModeSliderGroup(
    icon: ImageVector?,
    label: String,
    amp: Int,
    dur: Int,
    bandStart: Int,
    bandEnd: Int,
    activeBands: List<Int>?,
    editable: Boolean,
    onAmp: (Int) -> Unit,
    onDur: (Int) -> Unit,
    onBandRange: (Int, Int) -> Unit,
    onActiveBands: (List<Int>?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val useRange = activeBands == null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = label,
                color = MiuixTheme.colorScheme.onSurfaceContainer,
            )
        }
        SliderRow(
            label = "振幅",
            value = amp.toFloat(),
            valueText = "$amp%",
            editable = editable,
            valueRange = 0f..100f,
            onValue = onAmp,
        )
        Spacer(Modifier.height(14.dp))
        SliderRow(
            label = "时长",
            value = dur.toFloat(),
            valueText = "${dur}ms",
            editable = editable,
            valueRange = 10f..300f,
            onValue = onDur,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (expanded) "收起频段设置 ▲" else buildString {
                append("频段设置 ▼ · ")
                if (useRange) {
                    if (bandStart == 0 && bandEnd == 31) {
                        append("全频段")
                    } else {
                        append("范围 #${bandStart}(${BandLabels.label(bandStart)}) - #${bandEnd}(${BandLabels.label(bandEnd)})")
                    }
                } else {
                    val count = activeBands?.size ?: 0
                    if (count == 32) append("全频段") else append("已选 $count 个频段")
                }
            },
            color = MiuixTheme.colorScheme.primary,
            fontSize = 12.sp,
            modifier = Modifier.clickable { expanded = !expanded },
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("模式:", color = MiuixTheme.colorScheme.onSurfaceContainerVariant, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "范围",
                    color = if (useRange) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (useRange) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainerHighest)
                        .then(if (!useRange) Modifier.clickable { onActiveBands(null) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "频段",
                    color = if (!useRange) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceContainerVariant,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (!useRange) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceContainerHighest)
                        .then(if (useRange) Modifier.clickable { onActiveBands(activeBands ?: (bandStart..bandEnd).toList()) } else Modifier)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (useRange) {
                Text("范围: #${bandStart}(${BandLabels.label(bandStart)}) - #${bandEnd}(${BandLabels.label(bandEnd)})", color = MiuixTheme.colorScheme.primary, fontSize = 11.sp)
                BandRangeSlider(
                    start = bandStart,
                    end = bandEnd,
                    onStartChange = { s -> onBandRange(s, bandEnd) },
                    onEndChange = { e -> onBandRange(bandStart, e) },
                    enabled = editable,
                )
            } else {
                BandGridSelector(
                    selected = activeBands ?: emptyList(),
                    onToggle = { idx ->
                        val new = (activeBands ?: emptyList()).let { list ->
                            if (idx in list) list - idx else list + idx
                        }.sorted()
                        onActiveBands(new.ifEmpty { null })
                    },
                    enabled = editable,
                )
            }
        }
    }
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueText: String,
    editable: Boolean,
    valueRange: ClosedFloatingPointRange<Float>,
    onValue: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
            modifier = Modifier.width(40.dp),
        )
        Slider(
            value = value,
            onValueChange = { if (editable) onValue(it.toInt()) },
            modifier = Modifier.weight(1f),
            enabled = editable,
            valueRange = valueRange,
        )
        Text(
            text = valueText,
            color = MiuixTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.width(46.dp),
        )
    }
}

private object BandLabels {
    private val labels = arrayOf(
        "30Hz", "45Hz", "65Hz", "95Hz", "135Hz", "195Hz",
        "280Hz", "400Hz", "570Hz", "810Hz", "1.1kHz", "1.6kHz",
        "2.3kHz", "3.3kHz", "4.7kHz", "6.7kHz", "9.5kHz", "10.8kHz",
        "12.3kHz", "13.0kHz", "13.7kHz", "14.1kHz", "14.5kHz", "14.8kHz",
        "15.1kHz", "15.3kHz", "15.5kHz", "15.6kHz", "15.7kHz", "15.8kHz",
        "15.9kHz", "16.0kHz",
    )
    fun label(idx: Int): String = labels.getOrElse(idx) { "$idx" }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeAppsEditor(
    scopeApps: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val ctx = LocalContext.current.applicationContext
    var apps by remember { mutableStateOf(AppListCache.apps ?: emptyList()) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (apps.isEmpty()) {
            withContext(Dispatchers.IO) { AppListCache.load(ctx) }
            apps = AppListCache.apps ?: emptyList()
        }
    }

    val filtered = remember(apps, query) {
        val q = query.trim()
        apps.filter { !it.system && (q.isEmpty() || it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true)) }
    }

    if (scopeApps.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            scopeApps.forEach { pkg ->
                val appLabel = remember(pkg) {
                    try {
                        ctx.packageManager.getApplicationLabel(
                            ctx.packageManager.getApplicationInfo(pkg, 0)
                        ).toString()
                    } catch (_: Throwable) { pkg.substringAfterLast('.') }
                }
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceContainerHighest)
                        .clickable { onRemove(pkg) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = appLabel, color = MiuixTheme.colorScheme.onSurfaceContainer, fontSize = 12.sp)
                    Spacer(Modifier.width(4.dp))
                    Text(text = "✕", color = MiuixTheme.colorScheme.onSurfaceContainerVariant, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    if (showPicker) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("选择应用", color = MiuixTheme.colorScheme.onSurfaceContainer, fontSize = 14.sp)
                    Text("关闭", color = MiuixTheme.colorScheme.primary, modifier = Modifier.clickable { showPicker = false }, fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = "搜索",
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(300.dp)) {
                    items(filtered.size) { idx ->
                        val app = filtered[idx]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onAdd(app.pkg) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = app.label, color = MiuixTheme.colorScheme.onSurfaceContainer, fontSize = 14.sp)
                                Text(text = app.pkg, color = MiuixTheme.colorScheme.onSurfaceContainerVariant, fontSize = 11.sp)
                            }
                            Icon(MiuixIcons.Add, contentDescription = "添加", tint = MiuixTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }

    Text(
        text = if (!showPicker) (if (scopeApps.isEmpty()) "添加应用" else "继续添加") else "",
        color = MiuixTheme.colorScheme.primary,
        modifier = Modifier
            .clickable { if (!showPicker) { showPicker = true; query = "" } }
            .padding(vertical = 4.dp),
    )
}

@Composable
private fun BandGridSelector(
    selected: List<Int>,
    onToggle: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        for (row in 0..3) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (col in 0..7) {
                    val idx = row * 8 + col
                    val isSelected = idx in selected
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (isSelected) {
                                    if (enabled) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.primary.copy(alpha = 0.35f)
                                } else MiuixTheme.colorScheme.surfaceContainerHighest
                            )
                            .then(if (enabled) Modifier.clickable { onToggle(idx) } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "$idx",
                            color = if (isSelected) {
                                if (enabled) MiuixTheme.colorScheme.onPrimary
                                else MiuixTheme.colorScheme.onSurfaceContainerVariant
                            } else MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            fontSize = 10.sp,
                        )
                    }
                }
            }
            if (row < 3) Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun BandRangeSlider(
    start: Int,
    end: Int,
    onStartChange: (Int) -> Unit,
    onEndChange: (Int) -> Unit,
    enabled: Boolean = true,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "起始", color = MiuixTheme.colorScheme.onSurfaceContainerVariant, modifier = Modifier.width(36.dp), fontSize = 12.sp)
            Slider(
                value = start.toFloat(),
                onValueChange = { onStartChange(it.toInt()) },
                valueRange = 0f..31f,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "结束", color = MiuixTheme.colorScheme.onSurfaceContainerVariant, modifier = Modifier.width(36.dp), fontSize = 12.sp)
            Slider(
                value = end.toFloat(),
                onValueChange = { onEndChange(it.toInt()) },
                valueRange = 0f..31f,
                modifier = Modifier.weight(1f),
                enabled = enabled,
            )
        }
    }
}
