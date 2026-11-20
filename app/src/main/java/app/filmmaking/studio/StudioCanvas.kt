package app.filmmaking.studio

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

/** Decodes a `data:image/...;base64,` URI once per value; anything else (or a bad value) gives null. */
@Composable
fun rememberDataImage(uri: String?): ImageBitmap? = remember(uri) {
    if (uri == null || !uri.startsWith("data:image/")) null
    else runCatching {
        val bytes = android.util.Base64.decode(uri.substringAfter(","), android.util.Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
    }.getOrNull()
}

private fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()

/** Strokes are the web's portable vector: points in 0..1 of width / height, width in 1/400 of the frame width. */
private fun DrawScope.drawStrokes(strokes: List<Pair<List<Offset>, Float>>, color: Color) {
    strokes.forEach { (points, w) ->
        if (points.isEmpty()) return@forEach
        val px = points.map { Offset(it.x * size.width, it.y * size.height) }
        val sw = (w / 400f) * size.width
        if (px.size == 1) drawCircle(color, sw / 2, px[0])
        else drawPath(Path().apply { moveTo(px[0].x, px[0].y); px.drop(1).forEach { lineTo(it.x, it.y) } }, color,
            style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

private fun parseStrokes(a: JSONArray?): List<Pair<List<Offset>, Float>> = a.objects().map { s ->
    val pts = s.optJSONArray("points") ?: JSONArray()
    (0 until pts.length()).mapNotNull { i -> pts.optJSONArray(i)?.let { Offset(it.optDouble(0).toFloat(), it.optDouble(1).toFloat()) } } to
        s.optDouble("width", 3.0).toFloat()
}

/** One storyboard frame: image, sketch overlay, archive passage and the conceptual badge, in 16:9. */
@Composable
fun FrameView(frame: JSONObject, modifier: Modifier = Modifier) {
    val image = rememberDataImage(frame.optString("imageUri").ifBlank { null })
    val strokes = remember(frame.optJSONArray("strokes")?.toString()) { parseStrokes(frame.optJSONArray("strokes")) }
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.aspectRatio(16f / 9f).border(1.dp, MaterialTheme.colorScheme.outlineVariant).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
        .semantics { contentDescription = "Storyboard v" + frame.optInt("version") }) {
        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (strokes.isNotEmpty()) Canvas(Modifier.fillMaxSize()) { drawStrokes(strokes, ink) }
        frame.optJSONObject("archiveRef")?.takeIf { image == null }?.let { r ->
            Text("“" + r.optString("excerpt").take(160) + "…”\n— " + r.optString("title"), Modifier.padding(6.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (frame.optBoolean("conceptual")) Text("Conceptual AI reference — not technically accurate",
            Modifier.align(Alignment.TopStart).padding(4.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)).padding(horizontal = 4.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Finger sketching over the chosen frame (shown at 60 %); saves strokes in 0..1 coordinates rounded to 3 decimals. */
@Composable
fun SketchPad(baseImage: String?, onSave: (JSONArray) -> Unit, onCancel: () -> Unit) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    val base = rememberDataImage(baseImage)
    val ink = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).border(1.dp, MaterialTheme.colorScheme.outline)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))) {
            if (base != null) Image(base, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = 0.6f)
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Sketch area" }.pointerInput(Unit) {
                fun norm(o: Offset) = Offset((round(o.x / size.width * 1000) / 1000).coerceIn(0f, 1f), (round(o.y / size.height * 1000) / 1000).coerceIn(0f, 1f))
                detectDragGestures(
                    onDragStart = { current = listOf(norm(it)) },
                    onDrag = { change, _ -> change.consume(); current = current + norm(change.position) },
                    onDragEnd = { if (current.isNotEmpty()) strokes.add(current); current = emptyList() },
                    onDragCancel = { current = emptyList() }
                )
            }.pointerInput(Unit) { detectTapGestures { o -> strokes.add(listOf(Offset(o.x / size.width, o.y / size.height))) } }) {
                drawStrokes((strokes + listOf(current)).map { it to 3f }, ink)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = strokes.isNotEmpty(), onClick = {
                onSave(JSONArray().also { out -> strokes.forEach { s ->
                    out.put(JSONObject().put("width", 3).put("points", JSONArray().also { p -> s.forEach { o -> p.put(JSONArray().put(o.x.toDouble()).put(o.y.toDouble())) } }))
                } })
            }) { Text("Save sketch") }
            OutlinedButton(enabled = strokes.isNotEmpty(), onClick = { strokes.removeAt(strokes.lastIndex) }) { Text("Undo") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

private enum class Glyph { CAMERA, SUBJECT, BLOCK, LINE, OPENING, MOVE, LIGHT, BOUNCE }
private fun glyph(type: String) = when (type) {
    "camera" -> Glyph.CAMERA; "subject" -> Glyph.SUBJECT; "furniture" -> Glyph.BLOCK; "wall" -> Glyph.LINE
    "door", "window" -> Glyph.OPENING; "cameraMove", "subjectMove" -> Glyph.MOVE
    "key", "fill", "back", "practical", "natural" -> Glyph.LIGHT; else -> Glyph.BOUNCE
}
private fun tint(type: String) = when (type) {
    "camera", "cameraMove" -> Color(0xFF2F6FDE); "subject", "subjectMove" -> Color(0xFFD9534F); "furniture" -> Color(0xFF8D6E63)
    "wall" -> Color(0xFF616161); "door" -> Color(0xFF795548); "window" -> Color(0xFF4FC3F7)
    "key" -> Color(0xFFFFB300); "fill" -> Color(0xFFFFD54F); "back" -> Color(0xFFFF8F00); "practical" -> Color(0xFFFFCA28); "natural" -> Color(0xFF81D4FA)
    "reflector" -> Color(0xFFB0BEC5); "diffuser" -> Color(0xFFE0E0E0); else -> Color(0xFF212121)
}

/**
 * Top-down set plan in metres. Tap selects, drag moves (committed once on release, so an approved
 * diagram forks one draft per move rather than per frame). 1 m grid; rotation points the camera/light.
 */
@Composable
fun DiagramCanvas(diagram: JSONObject, selected: String?, onSelect: (String?) -> Unit, onMove: (String, Double, Double) -> Unit) {
    val w = diagram.optDouble("width", 10.0).coerceAtLeast(1.0).toFloat()
    val h = diagram.optDouble("height", 8.0).coerceAtLeast(1.0).toFloat()
    val elements = remember(diagram.optJSONArray("elements")?.toString()) { diagram.optJSONArray("elements").objects() }
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragAt by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurface
    val highlight = MaterialTheme.colorScheme.primary
    val currentElements by rememberUpdatedState(elements)
    val select by rememberUpdatedState(onSelect)
    val move by rememberUpdatedState(onMove)
    fun nearest(m: Offset): JSONObject? = currentElements.minByOrNull { hypot(it.optDouble("x").toFloat() - m.x, it.optDouble("y").toFloat() - m.y) }
        ?.takeIf { hypot(it.optDouble("x").toFloat() - m.x, it.optDouble("y").toFloat() - m.y) < 0.8f }
    Canvas(Modifier.fillMaxWidth().aspectRatio(w / h).border(1.dp, MaterialTheme.colorScheme.outline)
        .background(MaterialTheme.colorScheme.surface)
        .semantics { contentDescription = "Diagram canvas, " + elements.size + " elements" }
        .pointerInput(w, h) {
            fun metres(o: Offset) = Offset(o.x / size.width * w, o.y / size.height * h)
            detectTapGestures { o -> select(nearest(metres(o))?.optString("id")) }
        }
        .pointerInput(w, h) {
            fun metres(o: Offset) = Offset((o.x / size.width * w).coerceIn(0f, w), (o.y / size.height * h).coerceIn(0f, h))
            detectDragGestures(
                onDragStart = { o -> nearest(metres(o))?.let { e -> dragId = e.optString("id"); dragAt = metres(o); select(dragId) } },
                onDrag = { change, _ -> if (dragId != null) { change.consume(); dragAt = metres(change.position) } },
                onDragEnd = { dragId?.let { move(it, (round(dragAt.x * 10) / 10).toDouble(), (round(dragAt.y * 10) / 10).toDouble()) }; dragId = null },
                onDragCancel = { dragId = null }
            )
        }) {
        val k = size.width / w
        for (i in 0..w.toInt()) drawLine(gridColor, Offset(i * k, 0f), Offset(i * k, size.height), 1f)
        for (j in 0..h.toInt()) drawLine(gridColor, Offset(0f, j * k), Offset(size.width, j * k), 1f)
        elements.forEach { e ->
            val id = e.optString("id"); val type = e.optString("type")
            val at = if (id == dragId) dragAt else Offset(e.optDouble("x").toFloat(), e.optDouble("y").toFloat())
            val c = Offset(at.x * k, at.y * k); val color = tint(type)
            val rot = e.optDouble("rotation", 0.0).toFloat()
            val rad = Math.toRadians(rot.toDouble())
            val dir = Offset(cos(rad).toFloat(), sin(rad).toFloat())
            val end = if (e.has("x2") && e.has("y2")) Offset((e.optDouble("x2").toFloat() + at.x - e.optDouble("x").toFloat()) * k,
                (e.optDouble("y2").toFloat() + at.y - e.optDouble("y").toFloat()) * k) else c + dir * (1.5f * k)
            when (glyph(type)) {
                Glyph.CAMERA -> {
                    val fov = e.optDouble("fov", 50.0).toFloat()
                    drawArc(color.copy(alpha = 0.18f), rot - fov / 2, fov, true, c - Offset(2.5f * k, 2.5f * k), Size(5f * k, 5f * k))
                    rotate(rot, c) { drawRect(color, c - Offset(0.3f * k, 0.22f * k), Size(0.6f * k, 0.44f * k)) }
                }
                Glyph.SUBJECT -> { drawCircle(color, 0.3f * k, c); drawLine(Color.White, c, c + dir * (0.3f * k), 3f) }
                Glyph.BLOCK -> rotate(rot, c) { drawRect(color, c - Offset(0.5f * k, 0.3f * k), Size(1f * k, 0.6f * k)) }
                Glyph.LINE -> drawLine(color, c, end, 0.15f * k, StrokeCap.Square)
                Glyph.OPENING -> rotate(rot, c) { drawLine(color, c - Offset(0.45f * k, 0f), c + Offset(0.45f * k, 0f), 0.12f * k) }
                Glyph.MOVE -> {
                    drawLine(color, c, end, 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)))
                    val back = (c - end).let { v -> val l = hypot(v.x, v.y).coerceAtLeast(1f); v / l * (0.3f * k) }
                    drawLine(color, end, end + back + Offset(-back.y, back.x) * 0.6f, 3f); drawLine(color, end, end + back - Offset(-back.y, back.x) * 0.6f, 3f)
                }
                Glyph.LIGHT -> {
                    drawCircle(color, 0.25f * k, c)
                    drawLine(color.copy(alpha = 0.6f), c, c + dir * (1.2f * k), 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                Glyph.BOUNCE -> rotate(rot, c) { drawRect(color, c - Offset(0.5f * k, 0.08f * k), Size(1f * k, 0.16f * k)) }
            }
            if (id == selected || id == dragId) drawCircle(highlight, 0.55f * k, c, style = Stroke(3f))
            val label = e.optString("label").ifBlank { type }
            drawText(measurer, label.take(24), c + Offset(0.35f * k, 0.2f * k), TextStyle(fontSize = 10.sp, color = labelColor))
        }
    }
}
