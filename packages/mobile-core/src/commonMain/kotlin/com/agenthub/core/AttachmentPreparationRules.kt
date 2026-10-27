package com.agenthub.core

/** Local draft rules only. No upload, execution or platform permission is implied. */
object AttachmentPreparationRules {
    fun canReserve(existing: Int, reserved: Int, requested: Int): Boolean =
        canReserveWithinLimit(existing,reserved,requested,10)

    /** New UI may use a lower limit; legacy original manifests retain their existing ceiling. */
    fun canReserveWithinLimit(existing: Int,reserved: Int,requested: Int,maximum: Int): Boolean =
        maximum in 1..10 && existing >= 0 && reserved >= 0 && requested in 1..maximum && existing <= maximum &&
            reserved <= maximum-existing && requested <= maximum-existing-reserved

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
