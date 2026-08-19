package com.lyon.rhythmictouch.ui

import android.app.TimePickerDialog
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.lyon.rhythmictouch.R
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.ConfigStore
import com.lyon.rhythmictouch.config.QuietPeriod
import com.lyon.rhythmictouch.config.RhythmicConfig
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.basic.Check
import top.yukonga.miuix.kmp.icon.extended.Add
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun QuietPeriodScreen(
    store: ConfigStore,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(store.read()) }
    var editing by remember { mutableStateOf<QuietPeriod?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let {
            val json = QuietPeriod.toJsonList(config.quietPeriods)
            context.contentResolver.openOutputStream(it)?.use { os -> os.write(json.toByteArray()) }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val json = context.contentResolver.openInputStream(it)?.bufferedReader()?.readText()
            if (!json.isNullOrBlank()) {
                val imported = QuietPeriod.fromJsonList(json)
                if (imported.isNotEmpty()) {
                    config = config.copy(quietPeriods = config.quietPeriods + imported)
                    store.write(config)
                    context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                }
            }
        }
    }

    fun persist(newConfig: RhythmicConfig) {
        config = newConfig
        store.write(config)
        context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
    }

    BackHandler {
        if (showAdd || editing != null) { editing = null; showAdd = false } else onBack()
    }

    if (showAdd || editing != null) {
        QuietPeriodEditScreen(
            initial = editing,
            onSave = { period ->
                if (editing != null) {
                    persist(config.copy(quietPeriods = config.quietPeriods.map {
                        if (it.id == editing!!.id) period else it
                    }))
                } else {
                    persist(config.copy(quietPeriods = config.quietPeriods + period))
                }
                editing = null
                showAdd = false
            },
            onDelete = {
                persist(config.copy(quietPeriods = config.quietPeriods.filter { it.id != editing!!.id }))
                editing = null
                showAdd = false
            },
            onBack = { editing = null; showAdd = false },
        )
        return
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.screen_quiet_periods),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = -1f },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch("application/json") }) {
                        Icon(MiuixIcons.Import, contentDescription = stringResource(R.string.action_import))
                    }
                    IconButton(onClick = { exportLauncher.launch("quiet_periods.json") }) {
                        Icon(MiuixIcons.Backup, contentDescription = stringResource(R.string.action_export))
                    }
                    IconButton(onClick = { showAdd = true }) {
                        Icon(MiuixIcons.Add, contentDescription = stringResource(R.string.action_add))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            item { SmallTitle(stringResource(R.string.section_current_periods)) }

            if (config.quietPeriods.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(
                            text = stringResource(R.string.hint_no_quiet_periods),
                            modifier = Modifier.padding(16.dp),
                            color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        )
                    }
                }
            }

            items(config.quietPeriods.size, key = { config.quietPeriods[it].id }) { idx ->
                val period = config.quietPeriods[idx]
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editing = period }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = period.name.ifBlank { period.timeString() },
                                fontSize = 16.sp,
                            )
                            if (period.name.isNotBlank()) {
                                Text(
                                    text = period.timeString(),
                                    fontSize = 13.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                                )
                            }
                            Text(
                                text = if (period.repeatDaily) stringResource(R.string.quiet_repeat_daily) else stringResource(R.string.quiet_once),
                                fontSize = 12.sp,
                                color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                            )
                        }
                        Switch(
                            checked = period.enabled,
                            onCheckedChange = { enabled ->
                                persist(config.copy(quietPeriods = config.quietPeriods.map {
                                    if (it.id == period.id) it.copy(enabled = enabled) else it
                                }))
                            },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun QuietPeriodEditScreen(
    initial: QuietPeriod?,
    onSave: (QuietPeriod) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var startHour by remember { mutableIntStateOf(initial?.startHour ?: 22) }
    var startMinute by remember { mutableIntStateOf(initial?.startMinute ?: 0) }
    var endHour by remember { mutableIntStateOf(initial?.endHour ?: 7) }
    var endMinute by remember { mutableIntStateOf(initial?.endMinute ?: 0) }
    var repeatDaily by remember { mutableStateOf(initial?.repeatDaily ?: true) }

    BackHandler { onBack() }

    fun pickTime(hour: Int, minute: Int, onPicked: (Int, Int) -> Unit) {
        TimePickerDialog(context, { _, h, m -> onPicked(h, m) }, hour, minute, true).show()
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = if (initial == null) stringResource(R.string.screen_add_period) else stringResource(R.string.screen_edit_period),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier.size(24.dp).graphicsLayer { scaleX = -1f },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        onSave(
                            (initial ?: QuietPeriod()).copy(
                                name = name,
                                startHour = startHour,
                                startMinute = startMinute,
                                endHour = endHour,
                                endMinute = endMinute,
                                repeatDaily = repeatDaily,
                            )
                        )
                    }) {
                        Icon(MiuixIcons.Basic.Check, contentDescription = stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            SmallTitle(stringResource(R.string.label_period_name))
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (name.isEmpty()) {
                            Text(stringResource(R.string.hint_period_name), color = MiuixTheme.colorScheme.onSurfaceContainerVariant)
                        }
                        inner()
                    },
                )
            }

            SmallTitle(stringResource(R.string.label_start_time))
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onClick = { pickTime(startHour, startMinute) { h, m -> startHour = h; startMinute = m } },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "%02d:%02d".format(startHour, startMinute),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            SmallTitle(stringResource(R.string.label_end_time))
            Card(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                onClick = { pickTime(endHour, endMinute) { h, m -> endHour = h; endMinute = m } },
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "%02d:%02d".format(endHour, endMinute),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            SmallTitle(stringResource(R.string.section_options))
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { repeatDaily = !repeatDaily }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(stringResource(R.string.label_repeat_daily))
                    Switch(checked = repeatDaily, onCheckedChange = { repeatDaily = it })
                }
            }

            if (initial != null) {
                Spacer(Modifier.height(24.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = onDelete,
                ) {
                    Text(stringResource(R.string.action_delete), modifier = Modifier.padding(16.dp), fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(80.dp))
        }
    }
}
