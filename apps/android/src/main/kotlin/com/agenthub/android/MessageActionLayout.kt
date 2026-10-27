package com.agenthub.android

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

/** The list's floating latest control has its own trailing lane. Sheets reserve none. */
internal val LocalMessageActionEndInset=staticCompositionLocalOf { 0.dp }
