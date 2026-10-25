package io.github.ernstkl.mtpv.core

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Dark pages: light and dark swap, colours keep their hue, so white paper turns black, black notes
 * white, and red notes stay red. PdfRenderer has no such mode, so the rendered page goes through
 * [invert] and note colours through [pen] and [marker].
 */
object DarkPages {
    /**
     * Grays are inverted in linear light, not in their sRGB value. PdfRenderer blends antialiased
     * edges in sRGB, which makes a half-covered pixel of a black line darker than half: right for
     * hairlines on paper, but inverted in sRGB the same pixel turns too dim and thin lines (staves,
     * stems) look thinner than on paper. Inverted in linear light, it gets back the weight it had.
     */
    private val INVERT = IntArray(256) { toSrgb(1 - toLinear(it / 255.0)) }

    /**
     * Paper turns very dark gray, not black, and black notes off-white, not white: bright thin
     * lines on pure black blur for many eyes (halation). Every result is squeezed into PAPER..INK.
     */
    const val PAPER = 0x1E
    const val INK = 0xEB

    private fun squeeze(v: Float) = PAPER + v * (INK - PAPER) / 255

    /** Grays from [INVERT], squeezed. */
    private val GRAY = IntArray(256) { squeeze(INVERT[it].toFloat()).roundToInt() * 0x010101 }

    /**
     * Turns the hue by 180° (the luminance-keeping rotation of SVG's feHueRotate), so after the
     * inversion the hue comes back to where it was. A 3x3 matrix, row by row; grays stay as they are.
     */
    private val HUE_180 = floatArrayOf(
        -0.574f, 1.430f, 0.144f,
        0.426f, 0.430f, 0.144f,
        0.426f, 1.430f, -0.856f,
    )

    /**
     * [argb] with light and dark swapped and its hue kept; alpha stays. Inverted in linear light
     * ([INVERT]), a saturated colour would come out a pale tint, hard to tell from white. So
     * colours lean, by their saturation, towards plain inversion of the sRGB value.
     */
    fun pen(argb: Int): Int {
        val r = argb ushr 16 and 0xFF
        val g = argb ushr 8 and 0xFF
        val b = argb and 0xFF
        val alpha = argb and 0xFF000000.toInt()
        // Grays, nearly all of a page of sheet music, need neither the blend nor the hue rotation.
        if (r == g && g == b) return alpha or GRAY[r]
        val max = maxOf(r, g, b)
        val saturation = (max - minOf(r, g, b)) / max.toFloat()
        fun channel(v: Int) = INVERT[v] + (255 - v - INVERT[v]) * saturation
        val cr = channel(r)
        val cg = channel(g)
        val cb = channel(b)
        fun row(i: Int) = squeeze((HUE_180[3 * i] * cr + HUE_180[3 * i + 1] * cg + HUE_180[3 * i + 2] * cb).coerceIn(0f, 255f))
            .roundToInt()
        return alpha or (row(0) shl 16) or (row(1) shl 8) or row(2)
    }

    /** Every pixel of a rendered page through [pen], in place. */
    fun invert(pixels: IntArray) {
        for (i in pixels.indices) pixels[i] = pen(pixels[i])
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

    private fun toLinear(s: Double) = if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)

    private fun toSrgb(l: Double): Int {
        val s = if (l <= 0.0031308) l * 12.92 else 1.055 * l.pow(1 / 2.4) - 0.055
        return (s * 255).roundToInt().coerceIn(0, 255)
    }
}
