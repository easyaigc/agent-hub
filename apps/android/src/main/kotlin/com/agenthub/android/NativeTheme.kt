package com.agenthub.android

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** The existing app palette, shared with isolated native component acceptance. */
internal fun nativeThemeColors(dark: Boolean)=if(dark)
    darkColorScheme(background=Color.Black,surface=Color(0xFF1C1C1E),primary=Color.White,secondary=Color(0xFF98989D),onSurfaceVariant=Color(0xFF98989D))
else lightColorScheme(background=Color.White,surface=Color(0xFFF2F2F7),primary=Color.Black,secondary=Color(0xFF8A8A8E),onSurfaceVariant=Color(0xFF8A8A8E))

@Composable internal fun NativeTheme(dark: Boolean,language: String,content: @Composable ()->Unit) {
    ProductTextProvider(language) { MaterialTheme(colorScheme=nativeThemeColors(dark),content=content) }
}
