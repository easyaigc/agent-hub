package com.agenthub.android

import androidx.compose.runtime.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.agenthub.core.DesktopSuggestionRules
import com.agenthub.core.SuggestionDraft

/** Editor callbacks own a navigation and text revision, independent of attachments/settings. */
internal class NativeComposerTextOwner(var draft: SuggestionDraft,val navigation: String,val restoreFocus: Boolean=false) {
    var value by mutableStateOf(TextFieldValue(draft.text,TextRange(draft.text.length)))
    fun input(next: TextFieldValue,current: SuggestionDraft,currentNavigation: String,
        apply: (String)->SuggestionDraft): Boolean {
        if(navigation!=currentNavigation||!DesktopSuggestionRules.sameDraft(draft,current))return false
        value=next;draft=apply(next.text);return true
    }
}
