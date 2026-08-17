package com.lyon.rhythmictouch.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

@Composable
fun AppTheme(
    monetEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val controller = remember(monetEnabled) {
        ThemeController(
            colorSchemeMode = if (monetEnabled) ColorSchemeMode.MonetSystem else ColorSchemeMode.System,
        )
    }
    MiuixTheme(controller = controller) {
        content()
    }
}
