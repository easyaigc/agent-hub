package com.agenthub.android

import org.junit.Assert.*
import org.junit.Test

/** Pure observation boundaries, not a real DocsUI confirmation or glyph/screenshot proof. */
class NativeTreeConfirmationObservationRulesTest {
    private val folder="agent-hub-picker-3d00626f-726c-4a8f-a4e6-d8442da2feb4"
    private val screen=NativeTreeConfirmationObservation.Bounds(0,0,1000,2000)
    private val root=NativeTreeConfirmationObservation.Bounds(20,100,980,1800)
    private val inside=NativeTreeConfirmationObservation.Bounds(40,120,960,220)
    private fun witness(text: String?,visible: Boolean=true,password: Boolean=false,bounds: NativeTreeConfirmationObservation.Bounds=inside)=
        NativeTreeConfirmationObservation.folderVisible(folder,text,visible,password,bounds,root,screen)
    @Test fun onlyTheKnownOriginalAndExactDocsUiOwnerCanBeInspected() {
        assertTrue(NativeTreeConfirmationObservation.scoped("com.google.android.documentsui","com.google.android.documentsui",true))
        assertFalse(NativeTreeConfirmationObservation.scoped(null,"com.google.android.documentsui",true))
        assertFalse(NativeTreeConfirmationObservation.scoped("com.android.settings","com.google.android.documentsui",true))
        assertFalse(NativeTreeConfirmationObservation.scoped("com.google.android.documentsui","com.google.android.documentsui",false))
    }
    @Test fun completeOwnFolderTextAndContainedNativeBoundsCanBeObserved() { assertTrue(witness("Allow access to $folder?")) }
    @Test fun partialHiddenAndPasswordTextCannotSupplyTheFolderWitness() {
        assertFalse(witness(folder.dropLast(1)));assertFalse(witness(null));assertFalse(witness(folder,visible=false));assertFalse(witness(folder,password=true))
    }
    @Test fun clippedOrOffscreenNodesCannotSupplyTheFolderWitness() {
        assertFalse(witness(folder,bounds=inside.copy(left=0)));assertFalse(witness(folder,bounds=inside.copy(bottom=2001)))
        assertFalse(witness(folder,bounds=inside.copy(left=-40)));assertFalse(witness(folder,bounds=inside.copy(right=1001)))
    }
    @Test fun emptyAndInvertedRectanglesAreNeverContained() {
        assertFalse(screen.contains(inside.copy(right=inside.left)));assertFalse(screen.contains(inside.copy(top=300,bottom=200)))
        assertFalse(NativeTreeConfirmationObservation.Bounds(0,0,0,0).contains(inside));assertTrue(screen.contains(screen))
    }
}
