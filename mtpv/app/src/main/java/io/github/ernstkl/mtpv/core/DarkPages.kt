package io.github.ernstkl.mtpv.core

/**
 * Dark pages: light and dark swap, colours keep their hue, so white paper turns black, black notes
 * white, and red notes stay red. PdfRenderer has no such mode, so the page bitmap is drawn through
 * [MATRIX] and note colours go through [pen] and [marker].
 */
object DarkPages {
    /**
     * Inverts, then turns the hue by 180° (the luminance-keeping rotation of SVG's feHueRotate), so
     * the hue comes back to where it was. A 4x5 colour matrix, row by row, offsets in 0..255.
     */
    val MATRIX = floatArrayOf(
        0.574f, -1.430f, -0.144f, 0f, 255f,
        -0.426f, -0.430f, -0.144f, 0f, 255f,
        -0.426f, -1.430f, 0.856f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )

    /** [argb] through [MATRIX]. */
    fun pen(argb: Int): Int {
        val c = intArrayOf(argb ushr 16 and 0xFF, argb ushr 8 and 0xFF, argb and 0xFF)
        fun row(r: Int) = (MATRIX[5 * r] * c[0] + MATRIX[5 * r + 1] * c[1] + MATRIX[5 * r + 2] * c[2] + MATRIX[5 * r + 4])
            .toInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or (row(0) shl 16) or (row(1) shl 8) or row(2)
    }

    /**
     * Inverted, a light marker would turn almost black and vanish on the dark page. Instead it is
     * dimmed to [MARKER_SHARE] of its colour and drawn with Screen blending (the opposite of
     * Multiply on paper): black paper takes on the dimmed colour, light notes stay light.
     */
    fun marker(argb: Int): Int {
        fun dim(shift: Int) = ((argb ushr shift and 0xFF) * MARKER_SHARE).toInt() shl shift
        return (argb and 0xFF000000.toInt()) or dim(16) or dim(8) or dim(0)
    }

    const val MARKER_SHARE = 0.45f
}
