package com.dualframe.camera

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.opengl.GLES20
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Colour correction and grading. Everything that changes colour per pixel (light, white
 * balance, tone, colour wheels, per-colour HSL, saturation) is baked into the same 3D LUT the
 * app already uses for looks, so it previews live and exports for free. Vignette, grain and
 * sharpening look at neighbouring pixels or position, so they run as a small shader instead.
 *
 * Values are slider units: most run -100..100 with 0 = unchanged.
 */
data class ColorGrade(
    /** Stops, in tenths (-20..20 = -2..+2 EV). */
    val exposure: Int = 0,
    val contrast: Int = 0,
    val highlights: Int = 0,
    val shadows: Int = 0,
    val whites: Int = 0,
    val blacks: Int = 0,
    val temperature: Int = 0,
    val tint: Int = 0,
    val saturation: Int = 0,
    val vibrance: Int = 0,
    /** Lifted, matte blacks 0..100. */
    val fade: Int = 0,
    // Colour wheels: hue 0..359 and strength 0..100 for shadows, midtones, highlights.
    val shadowHue: Int = 210,
    val shadowAmount: Int = 0,
    val midHue: Int = 30,
    val midAmount: Int = 0,
    val highlightHue: Int = 40,
    val highlightAmount: Int = 0,
    /** Per colour (see [HSL_COLOURS]): hue shift, saturation, lightness, each -100..100. 24 values. */
    val hsl: List<Int> = List(24) { 0 },
    val vignette: Int = 0,
    val grain: Int = 0,
    val sharpen: Int = 0,
) {
    /** Nothing in the LUT part changes the picture. */
    val colourIsNeutral: Boolean
        get() = copy(vignette = 0, grain = 0, sharpen = 0, shadowHue = 210, midHue = 30, highlightHue = 40) == ColorGrade()

    val hasFinish get() = vignette != 0 || grain != 0 || sharpen != 0

    val isNeutral get() = colourIsNeutral && !hasFinish

    /** The colour part, on one pixel (display-referred RGB 0..1). */
    fun apply(p: FloatArray): FloatArray {
        var r = p[0]
        var g = p[1]
        var b = p[2]

        // White balance and exposure act on light, so work in (approximately) linear values.
        r = toLinear(r); g = toLinear(g); b = toLinear(b)
        val t = temperature / 100f
        val tn = tint / 100f
        r *= 1f + 0.25f * t
        b *= 1f - 0.25f * t
        g *= 1f - 0.18f * tn
        val gain = 2f.pow(exposure / 10f)
        r *= gain; g *= gain; b *= gain
        r = toDisplay(r); g = toDisplay(g); b = toDisplay(b)

        // Tone: blacks / shadows / highlights / whites move brightness in their own range.
        val l = luma(r, g, b)
        val delta = blacks / 100f * 0.18f * (1f - smooth(0f, 0.3f, l)) +
            shadows / 100f * 0.22f * bell(l, 0.28f, 0.22f) +
            highlights / 100f * 0.22f * bell(l, 0.72f, 0.22f) +
            whites / 100f * 0.18f * smooth(0.7f, 1f, l)
        if (delta != 0f) {
            val target = (l + delta).coerceIn(0f, 1f)
            if (l > 0.02f) {
                val k = target / l
                r *= k; g *= k; b *= k
            } else {
                r += delta; g += delta; b += delta
            }
        }

        // Contrast: an S around mid-grey that keeps blacks and whites in range.
        if (contrast != 0) {
            val k = contrast / 100f
            r = sCurve(r, k); g = sCurve(g, k); b = sCurve(b, k)
        }

        // Colour wheels: tint shadows, midtones and highlights without changing brightness.
        val l2 = luma(r, g, b)
        fun wheel(hue: Int, amount: Int, weight: Float) {
            if (amount == 0 || weight <= 0f) return
            val c = hueRgb(hue.toFloat())
            val strength = amount / 100f * 0.16f * weight
            val cl = luma(c[0], c[1], c[2])
            r += (c[0] - cl) * strength
            g += (c[1] - cl) * strength
            b += (c[2] - cl) * strength
        }
        wheel(shadowHue, shadowAmount, (1f - l2) * (1f - l2))
        wheel(midHue, midAmount, 1f - abs(2f * l2 - 1f))
        wheel(highlightHue, highlightAmount, l2 * l2)

        // Per-colour hue / saturation / lightness.
        if (hsl.any { it != 0 }) {
            val hsv = FloatArray(3)
            Color.RGBToHSV(byte(r), byte(g), byte(b), hsv)
            var hueShift = 0f
            var satScale = 0f
            var lum = 0f
            HSL_COLOURS.forEachIndexed { i, (_, centre) ->
                val d = hueDistance(hsv[0], centre)
                val w = (1f - d / 45f).coerceIn(0f, 1f)
                if (w > 0f) {
                    hueShift += hsl[i * 3] / 100f * 30f * w
                    satScale += hsl[i * 3 + 1] / 100f * w
                    lum += hsl[i * 3 + 2] / 100f * 0.25f * w
                }
            }
            // Only colourful pixels are affected; greys stay grey.
            val colourful = smooth(0.05f, 0.25f, hsv[1])
            hsv[0] = (hsv[0] + hueShift * colourful + 360f) % 360f
            hsv[1] = (hsv[1] * (1f + satScale * colourful)).coerceIn(0f, 1f)
            hsv[2] = (hsv[2] * (1f + lum * colourful)).coerceIn(0f, 1f)
            val c = Color.HSVToColor(hsv)
            r = Color.red(c) / 255f; g = Color.green(c) / 255f; b = Color.blue(c) / 255f
        }

        // Saturation, and vibrance (boosts muted colours more than vivid ones, kind to skin).
        val l3 = luma(r, g, b)
        val sat = saturation / 100f
        val currentSat = (max(r, max(g, b)) - min(r, min(g, b)))
        val vib = vibrance / 100f * (1f - currentSat)
        val s = 1f + sat + vib
        r = l3 + (r - l3) * s; g = l3 + (g - l3) * s; b = l3 + (b - l3) * s

        // Fade: matte blacks, softer whites.
        if (fade != 0) {
            val f = fade / 100f * 0.16f
            r = f + r * (1f - f * 1.4f); g = f + g * (1f - f * 1.4f); b = f + b * (1f - f * 1.4f)
        }
        return floatArrayOf(r.coerceIn(0f, 1f), g.coerceIn(0f, 1f), b.coerceIn(0f, 1f))
    }

    /** Compact text form for settings. */
    fun encode(): String = (listOf(
        exposure, contrast, highlights, shadows, whites, blacks, temperature, tint, saturation, vibrance, fade,
        shadowHue, shadowAmount, midHue, midAmount, highlightHue, highlightAmount, vignette, grain, sharpen,
    ) + hsl).joinToString(",")

    companion object {
        val HSL_COLOURS = listOf(
            "Red" to 0f, "Orange" to 30f, "Yellow" to 60f, "Green" to 120f,
            "Aqua" to 180f, "Blue" to 225f, "Purple" to 270f, "Magenta" to 315f,
        )

        fun decode(text: String?): ColorGrade {
            val v = text?.split(',')?.mapNotNull { it.trim().toIntOrNull() } ?: return ColorGrade()
            if (v.size < 20) return ColorGrade()
            return ColorGrade(
                exposure = v[0], contrast = v[1], highlights = v[2], shadows = v[3], whites = v[4], blacks = v[5],
                temperature = v[6], tint = v[7], saturation = v[8], vibrance = v[9], fade = v[10],
                shadowHue = v[11], shadowAmount = v[12], midHue = v[13], midAmount = v[14],
                highlightHue = v[15], highlightAmount = v[16], vignette = v[17], grain = v[18], sharpen = v[19],
                hsl = v.drop(20).take(24).let { if (it.size == 24) it else List(24) { 0 } },
            )
        }

        private fun toLinear(v: Float) = v.coerceAtLeast(0f).pow(2.2f)
        private fun toDisplay(v: Float) = v.coerceAtLeast(0f).pow(1f / 2.2f)
        private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b
        private fun smooth(a: Float, b: Float, x: Float): Float {
            val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }
        private fun bell(x: Float, centre: Float, width: Float): Float = (1f - abs(x - centre) / width / 2f).coerceIn(0f, 1f)
        private fun sCurve(v: Float, k: Float): Float {
            val x = v.coerceIn(0f, 1f)
            val s = if (x < 0.5f) 0.5f * (2f * x).pow(1f + k) else 1f - 0.5f * (2f * (1f - x)).pow(1f + k)
            return if (k >= 0f) s else 0.5f + (x - 0.5f) * (1f + k * 0.6f)
        }
        private fun hueRgb(h: Float): FloatArray {
            val c = Color.HSVToColor(floatArrayOf(h, 1f, 1f))
            return floatArrayOf(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
        }
        private fun hueDistance(a: Float, b: Float): Float {
            val d = abs(a - b) % 360f
            return if (d > 180f) 360f - d else d
        }
        private fun byte(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    }
}

/**
 * Vignette, film grain and sharpening for the finished videos (the live preview does the same
 * in its own shader).
 */
@OptIn(UnstableApi::class)
class GradeFinishEffect(private val grade: ColorGrade) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram = Program(grade, useHdr)

    private class Program(private val grade: ColorGrade, useHdr: Boolean) :
        BaseGlShaderProgram(/* useHighPrecisionColorComponents= */ useHdr, /* texturePoolCapacity= */ 1) {

        private val program: GlProgram = try {
            GlProgram(VERTEX, FRAGMENT).apply {
                setBufferAttribute("aFramePosition", GlUtil.getNormalizedCoordinateBounds(), GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
            }
        } catch (e: GlUtil.GlException) {
            throw VideoFrameProcessingException(e)
        }
        private var width = 1
        private var height = 1

        override fun configure(inputWidth: Int, inputHeight: Int): Size {
            width = inputWidth
            height = inputHeight
            return Size(inputWidth, inputHeight)
        }

        override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
            try {
                program.use()
                program.setSamplerTexIdUniform("uTexSampler", inputTexId, 0)
                program.setFloatsUniform("uTexel", floatArrayOf(1f / width, 1f / height))
                program.setFloatUniform("uVignette", grade.vignette / 100f)
                program.setFloatUniform("uGrain", grade.grain / 100f)
                program.setFloatUniform("uSharpen", grade.sharpen / 100f)
                program.setFloatUniform("uSeed", (presentationTimeUs % 100_000L) / 100_000f)
                program.bindAttributesAndUniforms()
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                GlUtil.checkGlError()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e, presentationTimeUs)
            }
        }

        override fun release() {
            super.release()
            try {
                program.delete()
            } catch (e: GlUtil.GlException) {
                throw VideoFrameProcessingException(e)
            }
        }
    }

    companion object {
        const val VERTEX = """
            attribute vec4 aFramePosition;
            varying vec2 vTexCoord;
            void main() {
              gl_Position = aFramePosition;
              vTexCoord = aFramePosition.xy * 0.5 + 0.5;
            }
        """

        /** Shared by the export and (adapted) the live preview. */
        const val FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexSampler;
            uniform vec2 uTexel;
            uniform float uVignette;
            uniform float uGrain;
            uniform float uSharpen;
            uniform float uSeed;
            varying vec2 vTexCoord;
            float rand(vec2 co) { return fract(sin(dot(co, vec2(12.9898, 78.233))) * 43758.5453); }
            void main() {
              vec4 c = texture2D(uTexSampler, vTexCoord);
              if (uSharpen > 0.0) {
                vec3 blur = (texture2D(uTexSampler, vTexCoord + vec2(uTexel.x, 0.0)).rgb
                  + texture2D(uTexSampler, vTexCoord - vec2(uTexel.x, 0.0)).rgb
                  + texture2D(uTexSampler, vTexCoord + vec2(0.0, uTexel.y)).rgb
                  + texture2D(uTexSampler, vTexCoord - vec2(0.0, uTexel.y)).rgb) * 0.25;
                c.rgb = c.rgb + (c.rgb - blur) * uSharpen * 1.5;
              }
              if (uVignette != 0.0) {
                vec2 d = vTexCoord - 0.5;
                float v = smoothstep(0.85, 0.25, length(d * vec2(1.0, 1.0)));
                c.rgb = mix(c.rgb, c.rgb * v, uVignette);
              }
              if (uGrain > 0.0) {
                float n = rand(vTexCoord * 1000.0 + uSeed) - 0.5;
                c.rgb += n * uGrain * 0.12;
              }
              gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0), c.a);
            }
        """
    }
}

/**
 * The grade controls, built into any container: used by Pro's Grade panel while shooting and
 * by the Grade screen for videos already in the library.
 */
class GradeControls(
    private val context: Context,
    private val container: LinearLayout,
    private var grade: ColorGrade,
    private val onChange: (ColorGrade) -> Unit,
) {
    private var tab = 0
    private var hslColour = 0

    private fun dp(v: Float) = Glass.dp(context, v).toInt()

    fun build() {
        container.removeAllViews()
        val tabs = ChipGroup(context).apply {
            chipSpacingHorizontal = dp(8f)
            chipSpacingVertical = dp(8f)
            setPadding(0, dp(8f), 0, dp(4f))
        }
        listOf("Light", "Colour", "Wheels", "HSL", "Finish").forEachIndexed { i, label ->
            tabs.addView(Glass.pill(context, label, i == tab) { tab = i; build() })
        }
        tabs.addView(Glass.pill(context, "Reset") { set(ColorGrade()) })
        container.addView(tabs)

        val g = grade
        when (tab) {
            0 -> {
                slider("Exposure", -20, 20, g.exposure, { "%+.1f EV".format(it / 10f) }) { set(grade.copy(exposure = it)) }
                slider("Contrast", -100, 100, g.contrast) { set(grade.copy(contrast = it)) }
                slider("Highlights", -100, 100, g.highlights) { set(grade.copy(highlights = it)) }
                slider("Shadows", -100, 100, g.shadows) { set(grade.copy(shadows = it)) }
                slider("Whites", -100, 100, g.whites) { set(grade.copy(whites = it)) }
                slider("Blacks", -100, 100, g.blacks) { set(grade.copy(blacks = it)) }
            }
            1 -> {
                slider("Temperature", -100, 100, g.temperature, { if (it < 0) "Cool $it" else if (it > 0) "Warm +$it" else "0" }) {
                    set(grade.copy(temperature = it))
                }
                slider("Tint", -100, 100, g.tint, { if (it < 0) "Green $it" else if (it > 0) "Magenta +$it" else "0" }) {
                    set(grade.copy(tint = it))
                }
                slider("Saturation", -100, 100, g.saturation) { set(grade.copy(saturation = it)) }
                slider("Vibrance", -100, 100, g.vibrance) { set(grade.copy(vibrance = it)) }
                slider("Fade", 0, 100, g.fade) { set(grade.copy(fade = it)) }
            }
            2 -> {
                note("Tint the shadows, midtones and highlights, like the colour wheels in an editor. Teal shadows + warm highlights is the classic film look.")
                slider("Shadows hue", 0, 359, g.shadowHue, { "$it°" }) { set(grade.copy(shadowHue = it)) }
                slider("Shadows amount", 0, 100, g.shadowAmount) { set(grade.copy(shadowAmount = it)) }
                slider("Midtones hue", 0, 359, g.midHue, { "$it°" }) { set(grade.copy(midHue = it)) }
                slider("Midtones amount", 0, 100, g.midAmount) { set(grade.copy(midAmount = it)) }
                slider("Highlights hue", 0, 359, g.highlightHue, { "$it°" }) { set(grade.copy(highlightHue = it)) }
                slider("Highlights amount", 0, 100, g.highlightAmount) { set(grade.copy(highlightAmount = it)) }
            }
            3 -> {
                note("Pick a colour, then shift its hue, saturation or lightness (greys aren't touched).")
                val colours = ChipGroup(context).apply { chipSpacingHorizontal = dp(8f) }
                ColorGrade.HSL_COLOURS.forEachIndexed { i, (name, hue) ->
                    colours.addView(Glass.pill(context, "●  $name", i == hslColour) { hslColour = i; build() }.apply {
                        val c = Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
                        text = android.text.SpannableString(text).apply {
                            setSpan(android.text.style.ForegroundColorSpan(c), 0, 1, 0)
                        }
                    })
                }
                container.addView(colours)
                val base = hslColour * 3
                fun setHsl(offset: Int, v: Int) = set(grade.copy(hsl = grade.hsl.toMutableList().also { it[base + offset] = v }))
                slider("Hue", -100, 100, g.hsl[base]) { setHsl(0, it) }
                slider("Saturation", -100, 100, g.hsl[base + 1]) { setHsl(1, it) }
                slider("Lightness", -100, 100, g.hsl[base + 2]) { setHsl(2, it) }
            }
            else -> {
                slider("Sharpen", 0, 100, g.sharpen) { set(grade.copy(sharpen = it)) }
                slider("Vignette", -100, 100, g.vignette, { if (it < 0) "Light $it" else "$it" }) { set(grade.copy(vignette = it)) }
                slider("Film grain", 0, 100, g.grain) { set(grade.copy(grain = it)) }
            }
        }
    }

    private fun set(new: ColorGrade) {
        val rebuild = new == ColorGrade() && grade != ColorGrade()
        grade = new
        onChange(new)
        if (rebuild) build()
    }

    private fun note(text: String) {
        container.addView(Glass.caption(context, text).apply { setPadding(0, dp(6f), 0, dp(4f)) })
    }

    private fun slider(
        label: String,
        from: Int,
        to: Int,
        value: Int,
        format: (Int) -> String = { if (it > 0) "+$it" else "$it" },
        onSet: (Int) -> Unit,
    ) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, 0)
        }
        val valueText = TextView(context).apply {
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            text = format(value)
        }
        row.addView(Glass.label(context, label), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(valueText)
        container.addView(row)
        container.addView(Slider(context).apply {
            valueFrom = from.toFloat()
            valueTo = to.toFloat()
            stepSize = 1f
            this.value = value.coerceIn(from, to).toFloat()
            setLabelFormatter { format(it.toInt()) }
            trackActiveTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.accent))
            trackInactiveTintList = ColorStateList.valueOf(0x26FFFFFF)
            setTickVisible(false)
            addOnChangeListener { _, v, fromUser ->
                valueText.text = format(v.toInt())
                if (fromUser) onSet(v.toInt())
            }
        })
    }
}
