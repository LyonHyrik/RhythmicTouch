package com.lyon.rhythmictouch.ui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyon.rhythmictouch.R
import com.lyon.rhythmictouch.RhythmicConstants
import com.lyon.rhythmictouch.config.DeviceConfigStore
import com.lyon.rhythmictouch.config.DeviceVibrationConfig
import com.lyon.rhythmictouch.config.ProfileExporter
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.icon.extended.Backup
import top.yukonga.miuix.kmp.icon.extended.Import
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun DeviceConfigListScreen(
    store: DeviceConfigStore,
    globalIntensity: Int,
    globalDelay: Int,
    onBack: () -> Unit,
) {
    val context = LocalContext.current.applicationContext
    val activityContext = LocalContext.current
    var devices by remember { mutableStateOf(store.readDevices()) }
    var rebuildTrigger by remember { mutableStateOf(0) }

    BackHandler { onBack() }

    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        rebuildTrigger++
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val bytes = ProfileExporter.deviceConfigJsonBytes(devices)
            ProfileExporter.writeToUri(context, uri, bytes)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes != null) {
                val imported = ProfileExporter.importDeviceConfigFromBytes(bytes)
                if (imported.isNotEmpty()) {
                    val existing = devices.associateBy { it.deviceAddress }
                    val merged = imported.map { imp ->
                        val old = existing[imp.deviceAddress]
                        imp.copy(deviceName = old?.deviceName ?: imp.deviceName, deviceType = old?.deviceType ?: imp.deviceType)
                    }
                    val allAddresses = merged.map { it.deviceAddress }.toSet()
                    val kept = devices.filter { it.deviceAddress !in allAddresses }
                    devices = merged + kept
                    store.writeDevices(devices)
                }
            }
        }
    }

    fun rebuildList() {
        val existing = devices.associateBy { it.deviceAddress }
        val built = mutableListOf<DeviceVibrationConfig>()
        built.add(
            existing["speaker"] ?: DeviceVibrationConfig(
                deviceAddress = "speaker",
                deviceName = activityContext.getString(R.string.device_speaker),
                deviceType = DeviceVibrationConfig.TYPE_SPEAKER,
            )
        )
        built.add(
            existing["headphone"] ?: DeviceVibrationConfig(
                deviceAddress = "headphone",
                deviceName = activityContext.getString(R.string.device_headphone),
                deviceType = DeviceVibrationConfig.TYPE_HEADPHONE,
            )
        )
        val hasBtPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            activityContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else true
        if (hasBtPermission) {
            try {
                val btManager = activityContext.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val btAdapter = btManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
                btAdapter?.bondedDevices?.forEach { btDevice ->
                    val addr = btDevice.address ?: return@forEach
                    built.add(
                        existing[addr] ?: DeviceVibrationConfig(
                            deviceAddress = addr,
                            deviceName = btDevice.name ?: addr,
                            deviceType = DeviceVibrationConfig.TYPE_BLUETOOTH,
                        )
                    )
                }
            } catch (_: Throwable) {}
        }
        devices = built
        store.writeDevices(built)
    }

    androidx.compose.runtime.LaunchedEffect(rebuildTrigger) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && activityContext.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            btPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            rebuildList()
        }
    }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.screen_device_config),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.Basic.ArrowRight,
                            contentDescription = stringResource(R.string.action_back),
                            modifier = Modifier
                                .size(24.dp)
                                .graphicsLayer { scaleX = -1f },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { importLauncher.launch("*/*") }) {
                        Icon(MiuixIcons.Import, contentDescription = stringResource(R.string.action_import))
                    }
                    IconButton(onClick = { exportLauncher.launch("device_config.json") }) {
                        Icon(MiuixIcons.Backup, contentDescription = stringResource(R.string.action_export))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            items(devices, key = { it.deviceAddress }) { device ->
                val index = devices.indexOf(device)
                DeviceCard(
                    device = device,
                    globalIntensity = globalIntensity,
                    globalDelay = globalDelay,
                    onToggle = { enabled ->
                        val updated = device.copy(enabled = enabled)
                        devices = devices.toMutableList().apply { set(index, updated) }
                        store.writeDevices(devices)
                    },
                    onIntensityChange = { value ->
                        val updated = device.copy(intensity = value)
                        devices = devices.toMutableList().apply { set(index, updated) }
                        store.writeDevices(devices)
                    },
                    onDelayChange = { value ->
                        val updated = device.copy(vibrationDelay = value)
                        devices = devices.toMutableList().apply { set(index, updated) }
                        store.writeDevices(devices)
                    },
                    onValueChangeFinished = {
                        context.sendBroadcast(Intent(RhythmicConstants.ACTION_REFRESH_CONFIG))
                    },
                )
            }
        }
    }
}

@Composable
private fun DeviceCard(
    device: DeviceVibrationConfig,
    globalIntensity: Int,
    globalDelay: Int,
    onToggle: (Boolean) -> Unit,
    onIntensityChange: (Int) -> Unit,
    onDelayChange: (Int) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .animateContentSize(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        color = MiuixTheme.colorScheme.onSurfaceContainer,
                        fontSize = 15.sp,
                    )
                    Text(
                        text = when (device.deviceType) {
                            DeviceVibrationConfig.TYPE_SPEAKER -> stringResource(R.string.device_speaker)
                            DeviceVibrationConfig.TYPE_HEADPHONE -> stringResource(R.string.device_headphone)
                            else -> device.deviceAddress
                        },
                        color = MiuixTheme.colorScheme.onSurfaceContainerVariant,
                        fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.width(12.dp))
                top.yukonga.miuix.kmp.basic.Switch(
                    checked = device.enabled,
                    onCheckedChange = onToggle,
                )
            }

            if (device.enabled) {
                Spacer(Modifier.height(12.dp))

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
                        text = "${device.intensity}%",
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = device.intensity.toFloat(),
                    onValueChange = { onIntensityChange(it.toInt()) },
                    valueRange = 0f..100f,
                    onValueChangeFinished = onValueChangeFinished,
                )

                Spacer(Modifier.height(8.dp))

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
                        text = "${device.vibrationDelay}ms",
                        color = MiuixTheme.colorScheme.primary,
                    )
                }
                Slider(
                    value = device.vibrationDelay.toFloat(),
                    onValueChange = { onDelayChange(it.toInt()) },
                    valueRange = 0f..300f,
                    onValueChangeFinished = onValueChangeFinished,
                )
            }
        }
    }
}
