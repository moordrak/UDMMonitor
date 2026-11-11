package com.moordrak.udmmonitor.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Fiksne boje za smjer prometa (ne ovise o dinamičkoj temi, da se uvijek razlikuju). */
object TrafficColors {
    val downLight = Color(0xFF2A78D6)
    val downDark = Color(0xFF3987E5)
    val upLight = Color(0xFFEB6834)
    val upDark = Color(0xFFD95926)

    val down: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) downDark else downLight
    val up: Color
        @Composable @ReadOnlyComposable get() = if (isSystemInDarkTheme()) upDark else upLight
}

@Composable
fun UdmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
