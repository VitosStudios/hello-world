package com.vitosstudios.suite.photo

/**
 * A named look: a bundle of [Adjustments]. Applying one replaces the adjustments (geometry is
 * kept) and is a normal undoable step, see [PhotoEditState.withPreset].
 */
data class FilterPreset(val id: String, val name: String, val adjustments: Adjustments)

object FilterPresets {
    val NONE = FilterPreset("none", "None", Adjustments.NONE)

    val ALL: List<FilterPreset> = listOf(
        NONE,
        FilterPreset("natural", "Natural", Adjustments(contrast = 8f, saturation = 6f, sharpness = 10f)),
        FilterPreset("vivid", "Vivid", Adjustments(contrast = 20f, saturation = 35f, sharpness = 20f)),
        FilterPreset("warm", "Warm", Adjustments(warmth = 35f, saturation = 10f, shadows = 10f)),
        FilterPreset("cool", "Cool", Adjustments(warmth = -35f, contrast = 5f)),
        FilterPreset("mono", "Mono", Adjustments(saturation = -100f, contrast = 12f)),
        FilterPreset("fade", "Fade", Adjustments(contrast = -30f, shadows = 30f, saturation = -20f)),
        FilterPreset("crisp", "Crisp", Adjustments(contrast = 15f, highlights = -15f, sharpness = 40f)),
        FilterPreset("noir", "Noir", Adjustments(exposure = -0.2f, contrast = 45f, shadows = -30f, saturation = -100f)),
    )

    fun byId(id: String?): FilterPreset? = ALL.firstOrNull { it.id == id }
}
