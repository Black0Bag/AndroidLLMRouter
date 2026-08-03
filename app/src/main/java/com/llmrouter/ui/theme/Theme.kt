package com.llmrouter.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import android.app.Activity
import android.os.Build
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext

// 配色方案
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1A73E8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF0B4A8F),
    secondary = Color(0xFF5F6368),
    onSecondary = Color.White,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1F1F1F),
    surface = Color.White,
    onSurface = Color(0xFF1F1F1F),
    surfaceVariant = Color(0xFFF1F3F4),
    onSurfaceVariant = Color(0xFF5F6368),
    error = Color(0xFFD93025),
    onError = Color.White,
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF0B4A8F),
    primaryContainer = Color(0xFF0B4A8F),
    onPrimaryContainer = Color(0xFFD3E3FD),
    secondary = Color(0xFF9AA0A6),
    onSecondary = Color(0xFF1F1F1F),
    background = Color(0xFF1F1F1F),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF292929),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF3C4043),
    onSurfaceVariant = Color(0xFF9AA0A6),
    error = Color(0xFFF28B82),
    onError = Color(0xFF1F1F1F),
)

@Composable
fun LlmRouterTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        // 安全地获取 Activity（避免 ClassCastException）
        var ctx = LocalContext.current
        while (ctx is android.content.ContextWrapper) {
            if (ctx is Activity) {
                ctx.window.statusBarColor = colorScheme.primary.toArgb()
                break
            }
            ctx = ctx.baseContext
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
