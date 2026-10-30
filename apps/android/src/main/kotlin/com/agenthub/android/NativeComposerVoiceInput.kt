package com.agenthub.android

import android.view.MotionEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange

/** The BasicTextField is the content, so its own native editing gestures remain installed.
 * Collapsed-caret touch holds reserve selection initiation for voice; an existing range keeps native
 * long-press selection/drag. This removes the competing native 500ms selection timer, rather than
 * racing two timers or moving voice before 500ms. Tap, double-tap, focus, IME and handle editing use
 * their original handlers. A gesture's configuration is latched until all pointers are up. */
@Composable internal fun NativeComposerVoiceInput(editor: Any,navigation: String,enabled: Boolean,
    selection: ()->TextRange,hint: String,actionLabel: String,onStart: ()->Boolean,
    content: @Composable (Modifier)->Unit) {
    val native=LocalViewConfiguration.current
    val reserved=remember(native) {object: ViewConfiguration by native {
        override val longPressTimeoutMillis: Long=Long.MAX_VALUE
    }}
    var inGesture by remember(editor,navigation) {mutableStateOf(false)}
    var reservedForGesture by remember(editor,navigation) {mutableStateOf(false)}
    val reserveAtRest=enabled&&selection().collapsed
    val reserve=if(inGesture)reservedForGesture else reserveAtRest
    val currentSelection by rememberUpdatedState(selection)
    val currentStart by rememberUpdatedState(onStart)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentReserve by rememberUpdatedState(reserve)
    val actions=if(!enabled)Modifier else Modifier.semantics {
        onLongClick(label=hint){currentStart()}
        customActions=listOf(CustomAccessibilityAction(actionLabel){currentStart()})
    }
    val pointer=Modifier.pointerInput(editor,navigation,enabled) {
        awaitEachGesture {
            val down=awaitFirstDown(requireUnconsumed=false,pass=PointerEventPass.Initial)
            reservedForGesture=currentReserve;inGesture=true
            try {
                val eligible=currentEnabled&&reservedForGesture&&currentSelection().collapsed&&
                    !down.isConsumed&&currentEvent.classification==MotionEvent.CLASSIFICATION_NONE&&down.type!=PointerType.Mouse&&currentEvent.changes.count {it.pressed}==1
                if(eligible) {
                    val ended=withTimeoutOrNull(500) {
                        // The native field normally consumes its first down to edit/focus on tap.
                        // That one baseline consumption is not another gesture's position claim.
                        awaitPointerEvent(PointerEventPass.Final)
                        var cancelled=!currentEnabled||!currentSelection().collapsed
                        while(!cancelled) {
                            val initial=awaitPointerEvent(PointerEventPass.Initial)
                            val change=initial.changes.singleOrNull {it.id==down.id}
                            cancelled=!currentEnabled||!currentSelection().collapsed||change==null||!change.pressed||
                                initial.changes.count {it.pressed}!=1||initial.classification!=MotionEvent.CLASSIFICATION_NONE||initial.changes.any {it.isConsumed}||
                                (change.position-down.position).getDistance()>viewConfiguration.touchSlop||
                                change.position.x<0||change.position.y<0||change.position.x>size.width||change.position.y>size.height
                            // A descendant or ancestor may claim a later event during Main.
                            val final=awaitPointerEvent(PointerEventPass.Final)
                            cancelled=cancelled||final.changes.any {it.isConsumed}||!currentSelection().collapsed
                        }
                        true
                    }
                    if(ended==null&&currentEnabled&&currentSelection().collapsed&&currentStart()) {
                        do {
                            val event=awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach {it.consume()}
                        }while(event.changes.any {it.pressed})
                    }
                }
                // Yielded gestures keep their native configuration until their own up/cancel.
                // In particular, selection changes from double-tap must not reset a live drag.
                while(currentEvent.changes.any {it.pressed})awaitPointerEvent(PointerEventPass.Final)
            }finally {inGesture=false}
        }
    }
    CompositionLocalProvider(LocalViewConfiguration provides if(reserve)reserved else native) {
        content(actions.then(pointer))
    }
}
