package com.agenthub.core

/** Local draft rules only. No upload, execution or platform permission is implied. */
object AttachmentPreparationRules {
    fun canReserve(existing: Int, reserved: Int, requested: Int): Boolean =
        existing >= 0 && reserved >= 0 && requested in 1..10 && existing <= 10 &&
            reserved <= 10 - existing && requested <= 10 - existing - reserved

    fun canCommit(capturedTarget: String, currentTarget: String, capturedGeneration: String,
        currentGeneration: String?, alreadyCompleted: Boolean): Boolean =
        capturedTarget.isNotEmpty() && capturedTarget == currentTarget &&
            capturedGeneration.isNotEmpty() && capturedGeneration == currentGeneration && !alreadyCompleted

    fun canSuggest(enabled: Boolean, readable: Boolean, baselineKnown: Boolean,
        ignored: Boolean, addedAtSeconds: Double?, nowSeconds: Double): Boolean =
        enabled && readable && baselineKnown && !ignored && addedAtSeconds != null &&
            nowSeconds.isFinite() && addedAtSeconds.isFinite() &&
            nowSeconds - addedAtSeconds in 0.0..600.0
}
