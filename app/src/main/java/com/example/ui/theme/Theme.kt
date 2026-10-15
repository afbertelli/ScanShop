package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val AppTypography = Typography()

// Crisp, high-contrast Light Color Scheme tailored for e-commerce
private val LightColorScheme = lightColorScheme(
    primary = AliExpressRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFECE8),
    onPrimaryContainer = Color(0xFF5A1000),
    secondary = ShopeeOrange,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFF0EC),
    onSecondaryContainer = Color(0xFF651B00),
    background = LightBackground,
    onBackground = LightTextPrimary,
    surface = LightSurface,
    onSurface = LightTextPrimary,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightTextSecondary,
    outline = LightBorder,
    outlineVariant = LightBorderSubtle
)

@Composable
fun AliShopTheme(
    darkTheme: Boolean = false, // Forced Light Mode as requested
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    // Strictly Pure Light Theme everywhere
    MaterialTheme(
        colorScheme = LightColorScheme,
        typography = AppTypography,
        content = content
    )
}

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) = AliShopTheme(darkTheme = false, dynamicColor = false, content = content)
