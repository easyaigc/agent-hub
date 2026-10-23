package com.agenthub.core

/** An explicit field action, including choosing a default already shown by an old draft. */
enum class GenerationPreferenceEdit { MODEL, EFFORT, PERMISSION, SPEED, ALL }
data class GenerationPreferenceValues(val model: String?,val effort: String?,val permissionMode: String?,val speed: String?)

/** Client defaults remember explicit choices; unrelated old-draft/runtime values never replace them. */
object GenerationPreferenceRules {
    fun remember(previous: GenerationPreferenceValues,current: GenerationPreferenceValues,edit: GenerationPreferenceEdit): GenerationPreferenceValues = when(edit) {
        GenerationPreferenceEdit.MODEL -> previous.copy(model=current.model,effort=current.effort,speed=current.speed)
        GenerationPreferenceEdit.EFFORT -> previous.copy(effort=current.effort)
        GenerationPreferenceEdit.PERMISSION -> previous.copy(permissionMode=current.permissionMode)
        GenerationPreferenceEdit.SPEED -> previous.copy(speed=current.speed)
        GenerationPreferenceEdit.ALL -> current
    }
}
