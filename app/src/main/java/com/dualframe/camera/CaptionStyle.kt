package com.dualframe.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import androidx.core.content.res.ResourcesCompat
import java.io.File
import java.util.Locale

/** How the word being spoken stands out. */
enum class CaptionHighlight(val label: String) {
    COLOR("Colour"), BOX("Box"), POP("Pop"), UNDERLINE("Underline"), NONE("None")
}

/** How each word arrives as it's spoken (the trending short-form entrances). */
enum class CaptionAnimation(val label: String) {
    NONE("Still"), POP_IN("Pop"), BOUNCE("Bounce"), SLIDE_UP("Slide up"), TYPEWRITER("Typewriter"),
    FLICKER("Flicker"), GLITCH("Glitch"), SHAKE("Shake")
}

/** Extra looks for the letters themselves. */
enum class CaptionEffect(val label: String) {
    NONE("None"), NEON("Neon"), GRADIENT("Gradient")
}

enum class CaptionBackground(val label: String) {
    NONE("None"), PILL("Pill"), BAR("Full bar"), WORDS("Word boxes")
}

enum class CaptionShadow(val label: String) {
    OFF("Off"), SOFT("Soft"), HARD("Hard"), LONG("Long")
}

enum class CaptionPosition(val label: String) {
    AUTO("Auto"), TOP("Top"), MIDDLE("Middle"), BOTTOM("Bottom")
}

/** Everything about how captions look. Colours are ARGB. */
data class CaptionStyle(
    /** A [CaptionFonts] id: "sys:…", "res:…" or "file:…". */
    val font: String = "res:montserrat",
    /** Size as a percentage of the default. */
    val sizePct: Int = 100,
    val textColor: Int = Color.WHITE,
    val highlightColor: Int = 0xFFE8C88C.toInt(),
    val highlight: CaptionHighlight = CaptionHighlight.COLOR,
    val animation: CaptionAnimation = CaptionAnimation.NONE,
    /** Outline thickness 0 (off) .. 4. */
    val outline: Int = 0,
    val outlineColor: Int = Color.BLACK,
    val shadow: CaptionShadow = CaptionShadow.SOFT,
    val shadowColor: Int = 0xA0000000.toInt(),
    /** Soft glow in the highlight colour around the spoken word. */
    val glow: Boolean = false,
    val background: CaptionBackground = CaptionBackground.PILL,
    val backgroundColor: Int = 0x8C0A0A0C.toInt(),
    val uppercase: Boolean = false,
    val position: CaptionPosition = CaptionPosition.AUTO,
    /** Words on screen at once; 0 = as many as fit. */
    val wordsPerLine: Int = 0,
    /** Karaoke build-up: words appear one by one as they're said. */
    val reveal: Boolean = false,
    val effect: CaptionEffect = CaptionEffect.NONE,
    /** Adds a fitting emoji after words like "money", "fire", "love", "paisa", "pyaar". */
    val autoEmoji: Boolean = false,
) {
    companion object {
        private const val PREFS = "caption_style"

        fun load(context: Context): CaptionStyle {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = CaptionStyle()
            fun <E : Enum<E>> e(key: String, values: Array<E>, def: E) =
                p.getString(key, null)?.let { n -> values.firstOrNull { it.name == n } } ?: def
            return CaptionStyle(
                font = p.getString("font", null) ?: d.font,
                sizePct = p.getInt("sizePct", d.sizePct),
                textColor = p.getInt("textColor", d.textColor),
                highlightColor = p.getInt("highlightColor", d.highlightColor),
                highlight = e("highlight", CaptionHighlight.entries.toTypedArray(), d.highlight),
                animation = e("animation", CaptionAnimation.entries.toTypedArray(), d.animation),
                outline = p.getInt("outline", d.outline),
                outlineColor = p.getInt("outlineColor", d.outlineColor),
                shadow = e("shadow", CaptionShadow.entries.toTypedArray(), d.shadow),
                shadowColor = p.getInt("shadowColor", d.shadowColor),
                glow = p.getBoolean("glow", d.glow),
                background = e("background", CaptionBackground.entries.toTypedArray(), d.background),
                backgroundColor = p.getInt("backgroundColor", d.backgroundColor),
                uppercase = p.getBoolean("uppercase", d.uppercase),
                position = e("position", CaptionPosition.entries.toTypedArray(), d.position),
                wordsPerLine = p.getInt("wordsPerLine", d.wordsPerLine),
                reveal = p.getBoolean("reveal", d.reveal),
                effect = e("effect", CaptionEffect.entries.toTypedArray(), d.effect),
                autoEmoji = p.getBoolean("autoEmoji", d.autoEmoji),
            )
        }

        fun save(context: Context, s: CaptionStyle) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("font", s.font)
                .putInt("sizePct", s.sizePct)
                .putInt("textColor", s.textColor)
                .putInt("highlightColor", s.highlightColor)
                .putString("highlight", s.highlight.name)
                .putString("animation", s.animation.name)
                .putInt("outline", s.outline)
                .putInt("outlineColor", s.outlineColor)
                .putString("shadow", s.shadow.name)
                .putInt("shadowColor", s.shadowColor)
                .putBoolean("glow", s.glow)
                .putString("background", s.background.name)
                .putInt("backgroundColor", s.backgroundColor)
                .putBoolean("uppercase", s.uppercase)
                .putString("position", s.position.name)
                .putInt("wordsPerLine", s.wordsPerLine)
                .putBoolean("reveal", s.reveal)
                .putString("effect", s.effect.name)
                .putBoolean("autoEmoji", s.autoEmoji)
                .apply()
        }

        /** Ready-made looks to start from. */
        val PRESETS: List<Pair<String, CaptionStyle>> = listOf(
            "Classic" to CaptionStyle(),
            "Bold pop" to CaptionStyle(
                font = "res:anton", sizePct = 125, uppercase = true, highlight = CaptionHighlight.POP,
                highlightColor = 0xFFFFE14D.toInt(), outline = 3, background = CaptionBackground.NONE,
                shadow = CaptionShadow.HARD, wordsPerLine = 3, animation = CaptionAnimation.POP_IN,
            ),
            "Boxed" to CaptionStyle(
                font = "res:poppins_extrabold", highlight = CaptionHighlight.BOX,
                highlightColor = 0xFFFF3B6B.toInt(), background = CaptionBackground.NONE, shadow = CaptionShadow.OFF,
                outline = 2,
            ),
            "Karaoke" to CaptionStyle(
                font = "res:montserrat", reveal = true, highlightColor = 0xFF4DE1FF.toInt(),
                glow = true, background = CaptionBackground.NONE, outline = 2,
            ),
            "Hype" to CaptionStyle(
                font = "res:archivo_black", sizePct = 130, uppercase = true, wordsPerLine = 2,
                highlight = CaptionHighlight.COLOR, highlightColor = 0xFF39FF7A.toInt(), outline = 4,
                background = CaptionBackground.NONE, shadow = CaptionShadow.HARD, animation = CaptionAnimation.BOUNCE,
                autoEmoji = true,
            ),
            "Neon flicker" to CaptionStyle(
                font = "res:bebas_neue", sizePct = 150, uppercase = true, effect = CaptionEffect.NEON,
                highlightColor = 0xFFFF2BD6.toInt(), highlight = CaptionHighlight.NONE, wordsPerLine = 3,
                background = CaptionBackground.NONE, shadow = CaptionShadow.OFF, animation = CaptionAnimation.FLICKER,
            ),
            "Glitch" to CaptionStyle(
                font = "res:anton", sizePct = 135, uppercase = true, wordsPerLine = 2,
                highlight = CaptionHighlight.NONE, background = CaptionBackground.NONE, outline = 2,
                shadow = CaptionShadow.OFF, animation = CaptionAnimation.GLITCH,
            ),
            "Typewriter" to CaptionStyle(
                font = "sys:monospace", highlight = CaptionHighlight.NONE, reveal = true,
                animation = CaptionAnimation.TYPEWRITER, background = CaptionBackground.PILL,
            ),
            "Sunset" to CaptionStyle(
                font = "res:poppins_extrabold", sizePct = 120, effect = CaptionEffect.GRADIENT,
                textColor = 0xFFFFD25A.toInt(), highlightColor = 0xFFFF4F8B.toInt(), highlight = CaptionHighlight.POP,
                background = CaptionBackground.NONE, outline = 2, shadow = CaptionShadow.SOFT,
                animation = CaptionAnimation.SLIDE_UP, wordsPerLine = 3,
            ),
            "One word" to CaptionStyle(
                font = "res:bebas_neue", sizePct = 170, wordsPerLine = 1, highlight = CaptionHighlight.NONE,
                background = CaptionBackground.NONE, outline = 3, shadow = CaptionShadow.LONG,
                animation = CaptionAnimation.POP_IN,
            ),
            "Handwritten" to CaptionStyle(
                font = "res:permanent_marker", highlight = CaptionHighlight.UNDERLINE,
                highlightColor = 0xFFFF8A3D.toInt(), background = CaptionBackground.NONE, shadow = CaptionShadow.SOFT,
            ),
            "Minimal" to CaptionStyle(
                font = "sys:sans-serif-medium", sizePct = 90, highlight = CaptionHighlight.NONE,
                background = CaptionBackground.BAR, backgroundColor = 0xB3000000.toInt(), shadow = CaptionShadow.OFF,
            ),
        )
    }
}

/** Fonts for captions: the phone's own, the ones bundled with the app, and any .ttf/.otf you import. */
object CaptionFonts {
    data class Font(val id: String, val name: String)

    val SYSTEM = listOf(
        Font("sys:sans-serif-black", "Sans Black"),
        Font("sys:sans-serif-medium", "Sans Medium"),
        Font("sys:sans-serif-condensed", "Condensed"),
        Font("sys:serif", "Serif"),
        Font("sys:monospace", "Mono"),
        Font("sys:casual", "Casual"),
        Font("sys:cursive", "Cursive"),
    )

    val BUNDLED = listOf(
        Font("res:montserrat", "Montserrat"),
        Font("res:anton", "Anton"),
        Font("res:bebas_neue", "Bebas Neue"),
        Font("res:poppins_extrabold", "Poppins"),
        Font("res:archivo_black", "Archivo Black"),
        Font("res:bangers", "Bangers"),
        Font("res:permanent_marker", "Permanent Marker"),
        Font("res:pacifico", "Pacifico"),
    )

    private fun dir(context: Context) = File(context.filesDir, "fonts").apply { mkdirs() }

    fun imported(context: Context): List<Font> =
        dir(context).listFiles { f -> f.extension.lowercase(Locale.US) in setOf("ttf", "otf") }
            ?.sortedBy { it.name.lowercase(Locale.US) }
            ?.map { Font("file:${it.name}", it.nameWithoutExtension) }
            .orEmpty()

    fun all(context: Context) = BUNDLED + SYSTEM + imported(context)

    fun name(context: Context, id: String) = all(context).firstOrNull { it.id == id }?.name ?: "Font"

    /** Copies a font file into the app; throws if it isn't a font Android can read. */
    fun import(context: Context, uri: Uri, displayName: String?): String {
        val name = (displayName ?: "Font.ttf").replace(Regex("[^A-Za-z0-9 ._-]"), "_")
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        require(ext == "ttf" || ext == "otf") { "Pick a .ttf or .otf font file" }
        val file = File(dir(context), name)
        context.contentResolver.openInputStream(uri)!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        runCatching { Typeface.createFromFile(file) }.getOrNull() ?: run {
            file.delete()
            error("That file isn't a font Android can read")
        }
        return "file:${file.name}"
    }

    fun delete(context: Context, id: String) {
        if (id.startsWith("file:")) File(dir(context), id.removePrefix("file:")).delete()
    }

    fun typeface(context: Context, id: String): Typeface {
        val fallback = Typeface.create("sans-serif", Typeface.BOLD)
        return runCatching {
            when {
                id.startsWith("sys:") -> Typeface.create(id.removePrefix("sys:"), Typeface.NORMAL)
                id.startsWith("res:") -> {
                    val res = context.resources.getIdentifier(id.removePrefix("res:"), "font", context.packageName)
                    if (res == 0) fallback else ResourcesCompat.getFont(context, res) ?: fallback
                }
                id.startsWith("file:") -> Typeface.createFromFile(File(dir(context), id.removePrefix("file:")))
                else -> fallback
            }
        }.getOrDefault(fallback)
    }

    /** Variable fonts default to their thinnest weight; captions want them heavy. */
    fun variation(id: String): String? = if (id == "res:montserrat") "'wght' 800" else null
}

/** A few words people say a lot, in English and Hinglish, and the emoji creators put after them. */
object CaptionEmoji {
    private val MAP = mapOf(
        "money" to "💰", "paisa" to "💰", "paise" to "💰", "rich" to "🤑", "cash" to "💵",
        "fire" to "🔥", "hot" to "🔥", "lit" to "🔥", "aag" to "🔥",
        "love" to "❤️", "pyaar" to "❤️", "pyar" to "❤️", "heart" to "❤️", "dil" to "❤️",
        "happy" to "😊", "khush" to "😊", "funny" to "😂", "laugh" to "😂", "lol" to "😂",
        "sad" to "😢", "cry" to "😢", "wow" to "🤯", "crazy" to "🤯", "mind" to "🧠", "idea" to "💡",
        "time" to "⏰", "fast" to "⚡", "growth" to "📈", "grow" to "📈", "win" to "🏆", "success" to "🏆",
        "food" to "🍕", "khana" to "🍛", "chai" to "☕", "coffee" to "☕", "music" to "🎵", "gaana" to "🎵",
        "travel" to "✈️", "home" to "🏠", "ghar" to "🏠", "work" to "💼", "kaam" to "💼",
        "phone" to "📱", "camera" to "📸", "video" to "🎬", "secret" to "🤫", "stop" to "🛑", "yes" to "✅",
        "haan" to "✅", "no" to "❌", "nahi" to "❌", "best" to "👑", "king" to "👑", "power" to "💪",
        "strong" to "💪", "gym" to "🏋️", "sleep" to "😴", "cool" to "😎", "think" to "🤔", "question" to "❓",
    )

    fun after(word: String): String? = MAP[word.lowercase(Locale.US).trim(',', '.', '!', '?', '"', '\'')]
}

/**
 * Draws one caption line in a [CaptionStyle]. Shared by the videos and the live preview,
 * so what you see while styling is exactly what gets burned in.
 */
class CaptionRenderer(private val style: CaptionStyle, typeface: Typeface, variation: String?) {

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        if (variation != null) fontVariationSettings = variation
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    /** True when the picture keeps changing while a word is on screen (needs a fresh frame often). */
    val animatesContinuously get() = style.animation == CaptionAnimation.SHAKE || style.animation == CaptionAnimation.FLICKER

    /**
     * [words] of the current line, [active] = the word being spoken, [phase] = 0..1 how far
     * into that word's entrance we are, [timeMs] for continuous effects. Returns a bitmap
     * [videoW] wide; the text stays inside the central safe area.
     */
    fun render(words: List<String>, active: Int, phase: Float, timeMs: Long, videoW: Int, videoH: Int): Bitmap {
        val vertical = videoH > videoW
        val base = minOf(videoW, videoH) * (if (vertical) 0.062f else 0.05f) * style.sizePct / 100f * sizeBoost()
        text.textSize = base
        val shown = (if (style.reveal) words.take(active + 1) else words).map { w ->
            val t = if (style.uppercase) w.uppercase(Locale.getDefault()) else w
            if (style.autoEmoji) CaptionEmoji.after(w)?.let { "$t $it" } ?: t else t
        }
        if (shown.isEmpty()) return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)

        val p = phase.coerceIn(0f, 1f)
        fun scaleOf(i: Int): Float {
            val entrance = if (i != active) 1f else when (style.animation) {
                CaptionAnimation.POP_IN -> 0.7f + 0.3f * easeOut(p)
                CaptionAnimation.BOUNCE -> bounce(p)
                else -> 1f
            }
            return entrance * if (i == active && style.highlight == CaptionHighlight.POP) 1.15f else 1f
        }
        val space = text.measureText(" ")
        val widths = shown.mapIndexed { i, w -> text.measureText(w) * scaleOf(i) }

        // Keep clear of the edges and the buttons apps lay over the sides: wrap inside the safe width.
        val maxWidth = videoW * safeWidth(vertical)
        val rows = mutableListOf(mutableListOf<Int>())
        var rowWidth = 0f
        shown.indices.forEach { i ->
            if (rows.last().isNotEmpty() && rowWidth + space + widths[i] > maxWidth && rows.size < 3) {
                rows += mutableListOf<Int>()
                rowWidth = 0f
            }
            rowWidth += (if (rows.last().isEmpty()) 0f else space) + widths[i]
            rows.last() += i
        }
        // A single word that's still too wide is shrunk to fit.
        val widest = rows.maxOf { row -> row.sumOf { widths[it].toDouble() }.toFloat() + space * (row.size - 1) }
        val fit = if (widest > maxWidth) maxWidth / widest else 1f
        val rowWidths = rows.map { row -> (row.sumOf { widths[it].toDouble() }.toFloat() + space * (row.size - 1)) * fit }

        val lineHeight = base * 1.3f * fit
        val padX = base * 0.55f
        val padY = base * 0.35f
        val extra = base * 0.6f // room for glow, shadows, bounce
        val height = (lineHeight * rows.size + padY * 2 + extra * 2).toInt()
        val bitmap = Bitmap.createBitmap(videoW and 1.inv(), (height + 1) and 1.inv(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val top = extra

        fill.color = style.backgroundColor
        val boxW = (rowWidths.maxOrNull() ?: 0f) + padX * 2
        when (style.background) {
            CaptionBackground.PILL -> {
                val left = (bitmap.width - boxW) / 2f
                val h = lineHeight * rows.size + padY * 2
                canvas.drawRoundRect(RectF(left, top, left + boxW, top + h), h * 0.2f, h * 0.2f, fill)
            }
            CaptionBackground.BAR -> {
                // A band across the safe width, not edge to edge.
                val w = videoW * safeWidth(vertical) + padX * 2
                val left = (bitmap.width - w) / 2f
                canvas.drawRect(left, top, left + w, top + lineHeight * rows.size + padY * 2, fill)
            }
            else -> Unit
        }

        rows.forEachIndexed { r, row ->
            var x = (bitmap.width - rowWidths[r]) / 2f
            val baseline = top + padY + lineHeight * r + base * fit * 1.02f
            for (i in row) {
                val w = widths[i] * fit
                val isActive = i == active
                val scale = scaleOf(i) * fit
                var dx = 0f
                var dy = 0f
                var alpha = 1f
                var word = shown[i]
                if (isActive) {
                    when (style.animation) {
                        CaptionAnimation.SLIDE_UP -> {
                            dy = base * 0.45f * (1f - easeOut(p))
                            alpha = easeOut(p)
                        }
                        CaptionAnimation.TYPEWRITER -> word = word.take(maxOf(1, (word.length * p).toInt() + 1).coerceAtMost(word.length))
                        CaptionAnimation.FLICKER -> alpha = flicker(p, timeMs)
                        CaptionAnimation.SHAKE -> {
                            dx = base * 0.035f * kotlin.math.sin(timeMs / 23.0).toFloat()
                            dy = base * 0.035f * kotlin.math.cos(timeMs / 31.0).toFloat()
                        }
                        else -> Unit
                    }
                }
                canvas.save()
                canvas.translate(dx, dy)
                // Scale around the word's centre so popping doesn't shift the line.
                canvas.scale(scale, scale, x + w / 2f, baseline - base * 0.35f)
                // The word's natural width is w / scale; centre it on the scaled slot.
                val drawX = x + (w - w / scale) / 2f
                val glitch = isActive && style.animation == CaptionAnimation.GLITCH && p < 1f
                drawWord(canvas, word, drawX, baseline, isActive, base, alpha, if (glitch) (1f - p) else 0f, timeMs)
                canvas.restore()
                x += w + space * fit
            }
        }
        return bitmap
    }

    private fun sizeBoost() = when (style.wordsPerLine) {
        1 -> 1.35f
        2 -> 1.15f
        else -> 1f
    }

    private fun drawWord(
        canvas: Canvas,
        word: String,
        x: Float,
        baseline: Float,
        active: Boolean,
        base: Float,
        alpha: Float,
        glitch: Float,
        timeMs: Long,
    ) {
        val w = text.measureText(word)
        val pad = base * 0.16f
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        fun withAlpha(color: Int) = Color.argb(Color.alpha(color) * a / 255, Color.red(color), Color.green(color), Color.blue(color))
        text.shader = null
        text.maskFilter = null

        if (style.background == CaptionBackground.WORDS) {
            fill.color = withAlpha(style.backgroundColor)
            canvas.drawRoundRect(RectF(x - pad, baseline - base, x + w + pad, baseline + base * 0.28f), pad, pad, fill)
        }
        if (active && style.highlight == CaptionHighlight.BOX) {
            fill.color = withAlpha(style.highlightColor)
            canvas.drawRoundRect(RectF(x - pad, baseline - base, x + w + pad, baseline + base * 0.28f), pad, pad, fill)
        }
        // Glow: the spoken word, or every word for neon.
        if ((active && style.glow) || style.effect == CaptionEffect.NEON) {
            text.style = Paint.Style.FILL
            text.color = withAlpha(style.highlightColor)
            text.maskFilter = BlurMaskFilter(base * (if (style.effect == CaptionEffect.NEON) 0.25f else 0.35f), BlurMaskFilter.Blur.NORMAL)
            text.clearShadowLayer()
            canvas.drawText(word, x, baseline, text)
            if (style.effect == CaptionEffect.NEON) canvas.drawText(word, x, baseline, text)
            text.maskFilter = null
        }

        // Glitch: offset red and cyan copies that jitter and settle.
        if (glitch > 0f) {
            val j = base * 0.09f * glitch * (if ((timeMs / 40) % 2 == 0L) 1f else -0.6f)
            text.style = Paint.Style.FILL
            text.clearShadowLayer()
            text.color = Color.argb(a * 200 / 255, 255, 40, 70)
            canvas.drawText(word, x + j, baseline - j * 0.3f, text)
            text.color = Color.argb(a * 200 / 255, 40, 230, 255)
            canvas.drawText(word, x - j, baseline + j * 0.3f, text)
        }

        text.style = Paint.Style.FILL
        text.clearShadowLayer()
        when (style.shadow) {
            CaptionShadow.LONG -> {
                text.color = withAlpha(style.shadowColor)
                for (s in 6 downTo 1) {
                    val d = base * 0.025f * s
                    canvas.drawText(word, x + d, baseline + d, text)
                }
            }
            CaptionShadow.HARD -> text.setShadowLayer(0.01f, base * 0.06f, base * 0.06f, withAlpha(style.shadowColor))
            CaptionShadow.SOFT -> text.setShadowLayer(base * 0.12f, 0f, base * 0.05f, withAlpha(style.shadowColor))
            CaptionShadow.OFF -> Unit
        }

        if (style.outline > 0) {
            text.style = Paint.Style.STROKE
            text.strokeJoin = Paint.Join.ROUND
            text.strokeWidth = base * 0.045f * style.outline
            text.color = withAlpha(style.outlineColor)
            canvas.drawText(word, x, baseline, text)
            text.style = Paint.Style.FILL
            text.clearShadowLayer()
        }

        val highlighted = active && style.highlight in setOf(CaptionHighlight.COLOR, CaptionHighlight.POP)
        val color = if (highlighted) style.highlightColor else style.textColor
        when {
            style.effect == CaptionEffect.GRADIENT && !highlighted -> {
                text.color = Color.WHITE
                text.alpha = a
                text.shader = android.graphics.LinearGradient(
                    0f, baseline - base, 0f, baseline + base * 0.2f,
                    style.textColor, style.highlightColor, android.graphics.Shader.TileMode.CLAMP,
                )
            }
            style.effect == CaptionEffect.NEON -> text.color = withAlpha(Color.WHITE)
            else -> text.color = withAlpha(color)
        }
        canvas.drawText(word, x, baseline, text)
        text.shader = null
        text.clearShadowLayer()

        if (active && style.highlight == CaptionHighlight.UNDERLINE) {
            fill.color = withAlpha(style.highlightColor)
            val y = baseline + base * 0.14f
            canvas.drawRoundRect(RectF(x, y, x + w, y + base * 0.1f), base * 0.05f, base * 0.05f, fill)
        }
    }

    private fun easeOut(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 1f - (1f - c) * (1f - c) * (1f - c)
    }

    /** Overshoots then settles, like a bounce. */
    private fun bounce(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return 0.6f + 0.4f * (1f + 2.70158f * (c - 1f) * (c - 1f) * (c - 1f) + 1.70158f * (c - 1f) * (c - 1f))
    }

    /** Neon-sign flicker as the word comes on, then a faint shimmer while it's lit. */
    private fun flicker(p: Float, timeMs: Long): Float {
        if (p < 1f) {
            val pattern = floatArrayOf(1f, 0.15f, 0.9f, 0.1f, 1f, 0.4f, 1f, 1f)
            return pattern[(p * (pattern.size - 1)).toInt().coerceIn(0, pattern.size - 1)]
        }
        return if ((timeMs / 70) % 23 == 0L) 0.55f else 1f
    }

    /** Share of the width captions may use: the central safe area. */
    private fun safeWidth(vertical: Boolean) = if (vertical) SAFE_WIDTH_VERTICAL else SAFE_WIDTH_WIDE

    /** Where the caption sits, in normalized device coordinates (y up). */
    fun anchorY(vertical: Boolean): Float = when (style.position) {
        CaptionPosition.AUTO -> if (vertical) AUTO_Y_VERTICAL else AUTO_Y_WIDE
        CaptionPosition.TOP -> if (vertical) 0.45f else 0.6f
        CaptionPosition.MIDDLE -> 0f
        CaptionPosition.BOTTOM -> if (vertical) -0.36f else -0.72f
    }

    companion object {
        /** How long a word's entrance takes. */
        const val ENTRANCE_MS = 280L

        // Safe area for captions (see docs/CAPTIONS.md): a little below the middle, well clear of
        // the edges, the right-hand buttons and the caption/username block apps put at the bottom.
        const val SAFE_WIDTH_VERTICAL = 0.72f
        const val SAFE_WIDTH_WIDE = 0.62f
        /** 60% down a tall video. */
        const val AUTO_Y_VERTICAL = -0.2f
        /** 70% down a wide one. */
        const val AUTO_Y_WIDE = -0.4f
    }
}
