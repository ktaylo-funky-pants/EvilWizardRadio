package com.example.evilwizardradio.ui.theme


import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7B4DFF),
    background = Color(0xFF121212),
    surface = Color(0xFF121212),
    onBackground = Color.White,
    onSurface = Color.White
)
@Composable
fun EvilWizardRadioTheme(
    content: @Composable () -> Unit
){

    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content
    )
}