package app.filmmaking.studio.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The web app's design-system components (screenshot-match src/components/studio/ui.tsx) for Compose.
 * Same names where possible so a web screen and its phone screen read alike.
 */

private val CardShape = RoundedCornerShape(18.dp)

@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = StudioColors.MutedForeground) =
    Text(text.uppercase(), modifier, style = EyebrowStyle.copy(color = color), maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Sticky top bar: optional back arrow, eyebrow above a Fraunces title, actions on the right. 64dp tall with a hairline. */
@Composable
fun StudioTopBar(title: String, eyebrow: String? = null, onBack: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.background(StudioColors.Background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconBtn(Lucide.ArrowLeft, "Back", onClick = onBack, tint = StudioColors.Foreground)
            Column(Modifier.weight(1f).padding(start = if (onBack == null) 12.dp else 0.dp)) {
                if (!eyebrow.isNullOrBlank()) Eyebrow(eyebrow)
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, content = actions)
        }
        HorizontalDivider(color = StudioColors.Border)
    }
}

@Composable
fun IconBtn(icon: ImageVector, label: String, onClick: () -> Unit, active: Boolean = false, tint: Color? = null) {
    IconButton(onClick = onClick, Modifier.size(44.dp)) {
        Icon(icon, label, Modifier.size(20.dp), tint = tint ?: if (active) StudioColors.Primary else StudioColors.MutedForeground)
    }
}

/** Section heading (Fraunces 18) with an optional small gold action on the right, e.g. "All". */
@Composable
fun SectionTitle(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp))
        if (action != null && onAction != null) Text(action, Modifier.clickable(role = Role.Button, onClick = onAction).padding(4.dp),
            style = MaterialTheme.typography.labelMedium, color = StudioColors.Primary)
    }
}

/** Card: rounded-xl, hairline border, card colour, soft shadow. Clickable when [onClick] is given. */
@Composable
fun StudioCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val base = modifier.shadow(12.dp, CardShape, ambientColor = Color.Black, spotColor = Color.Black)
        .clip(CardShape).background(StudioColors.Card).border(1.dp, StudioColors.Border, CardShape)
    Column((if (onClick != null) base.clickable(onClick = onClick) else base).padding(padding), content = content)
}

enum class ChipTone { Default, Gold, Success, Danger }

/** Pill chip. Active = gold border on gold-soft; tones colour the border and text. */
@Composable
fun StudioChip(text: String, active: Boolean = false, tone: ChipTone = ChipTone.Default, onClick: (() -> Unit)? = null, leading: ImageVector? = null) {
    val (border, fg, bg) = when {
        active -> Triple(StudioColors.Primary, StudioColors.Primary, StudioColors.GoldSoft)
        tone == ChipTone.Gold -> Triple(StudioColors.Primary.copy(alpha = 0.4f), StudioColors.Primary, StudioColors.Surface)
        tone == ChipTone.Success -> Triple(StudioColors.Success.copy(alpha = 0.4f), StudioColors.Success, StudioColors.Surface)
        tone == ChipTone.Danger -> Triple(StudioColors.Destructive.copy(alpha = 0.4f), StudioColors.Destructive, StudioColors.Surface)
        else -> Triple(StudioColors.Border, StudioColors.MutedForeground, StudioColors.Surface)
    }
    var m = Modifier.heightIn(min = 32.dp).clip(CircleShape).background(bg).border(1.dp, border, CircleShape)
    if (onClick != null) m = m.clickable(role = Role.Button, onClick = onClick)
    Row(m.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (leading != null) Icon(leading, null, Modifier.size(14.dp), tint = fg)
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** 6dp track with the gold gradient fill. */
@Composable
fun GoldProgress(percent: Float, modifier: Modifier = Modifier) {
    val v by animateFloatAsState(percent.coerceIn(0f, 100f) / 100f, label = "progress")
    Box(modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(StudioColors.Muted)) {
        if (v > 0f) Box(Modifier.fillMaxHeight().fillMaxWidth(v).clip(CircleShape).background(StudioColors.GoldGradient))
    }
}

enum class BtnVariant { Primary, Outline, Ghost, Danger }

/** The web `Btn`: a 48dp pill. Primary = amber, Outline = surface with hairline, Ghost = text only, Danger = red outline. */
@Composable
fun StudioButton(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier, variant: BtnVariant = BtnVariant.Primary,
    enabled: Boolean = true, icon: ImageVector? = null,
) {
    val colors = when (variant) {
        BtnVariant.Primary -> ButtonDefaults.buttonColors(StudioColors.Primary, StudioColors.PrimaryForeground,
            StudioColors.Primary.copy(alpha = 0.4f), StudioColors.PrimaryForeground.copy(alpha = 0.6f))
        BtnVariant.Outline -> ButtonDefaults.buttonColors(StudioColors.Surface, StudioColors.Foreground,
            StudioColors.Surface.copy(alpha = 0.4f), StudioColors.Foreground.copy(alpha = 0.4f))
        BtnVariant.Ghost -> ButtonDefaults.buttonColors(Color.Transparent, StudioColors.MutedForeground, Color.Transparent, StudioColors.MutedForeground.copy(alpha = 0.4f))
        BtnVariant.Danger -> ButtonDefaults.buttonColors(Color.Transparent, StudioColors.Destructive, Color.Transparent, StudioColors.Destructive.copy(alpha = 0.4f))
    }
    val border = when (variant) {
        BtnVariant.Outline -> BorderStroke(1.dp, StudioColors.Border)
        BtnVariant.Danger -> BorderStroke(1.dp, StudioColors.Destructive.copy(alpha = if (enabled) 0.4f else 0.16f))
        else -> null
    }
    Button(onClick, modifier.heightIn(min = 48.dp), enabled = enabled, shape = CircleShape, colors = colors, border = border,
        contentPadding = PaddingValues(horizontal = 20.dp)) {
        if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 2, textAlign = TextAlign.Center)
    }
}

/** Labelled field: eyebrow label, surface box with a hairline that turns amber on focus. */
@Composable
fun StudioField(
    label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String? = null, minLines: Int = 1, singleLine: Boolean = minLines == 1, hint: String? = null,
    numeric: Boolean = false,
) {
    // Tapping the label focuses the field, like an HTML <label for>.
    val focus = remember { FocusRequester() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.clickable(interactionSource = null, indication = null) { focus.requestFocus() }) { Eyebrow(label) }
        OutlinedTextField(value, onValueChange, Modifier.fillMaxWidth().focusRequester(focus), singleLine = singleLine, minLines = minLines,
            keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
            placeholder = placeholder?.let { { Text(it, color = StudioColors.MutedForeground.copy(alpha = 0.7f)) } },
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = if (minLines > 1) 24.sp else 20.sp),
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = StudioColors.Surface, unfocusedContainerColor = StudioColors.Surface,
                focusedBorderColor = StudioColors.Ring, unfocusedBorderColor = StudioColors.Input,
                cursorColor = StudioColors.Primary, focusedTextColor = StudioColors.Foreground, unfocusedTextColor = StudioColors.Foreground,
            ))
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground)
    }
}

/** Labelled dropdown in the field style (web `SelectField`). */
@Composable
fun StudioSelect(label: String, value: String, options: List<String>, onChange: (String) -> Unit, display: (String) -> String = { it }) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(label)
        Box {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(StudioColors.Surface)
                .border(1.dp, StudioColors.Input, RoundedCornerShape(12.dp)).clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(display(value).ifBlank { "—" }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp))
                Icon(Lucide.ChevronDown, null, Modifier.size(18.dp), tint = StudioColors.MutedForeground)
            }
            DropdownMenu(open, { open = false }) {
                options.forEach { o -> DropdownMenuItem(text = { Text(display(o)) }, onClick = { open = false; onChange(o) }) }
            }
        }
    }
}

/** Progressive disclosure card (web `Expandable`). */
@Composable
fun Expandable(title: String, subtitle: String? = null, defaultOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(defaultOpen) }
    val turn by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Column(Modifier.fillMaxWidth().clip(CardShape).background(StudioColors.Card).border(1.dp, StudioColors.Border, CardShape)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { open = !open }.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = StudioColors.MutedForeground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Lucide.ChevronDown, null, Modifier.size(20.dp).rotate(turn), tint = StudioColors.MutedForeground)
        }
        AnimatedVisibility(open) {
            Column {
                HorizontalDivider(color = StudioColors.Border)
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}

/** Dashed empty state (web `Empty`). */
@Composable
fun EmptyState(title: String, body: String? = null, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().drawBehind {
        drawRoundRect(StudioColors.Border.copy(alpha = 0.25f), cornerRadius = CornerRadius(18.dp.toPx()),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))))
    }.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp), textAlign = TextAlign.Center)
        if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium, color = StudioColors.MutedForeground, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(12.dp)); action() }
    }
}

/** Mono label and value (web `Meta`). */
@Composable
fun Meta(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Eyebrow(label)
        Text(value.ifBlank { "—" }, style = MonoStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun FutureTag() = Text("FUTURE", Modifier.drawBehind {
    drawRoundRect(StudioColors.MutedForeground.copy(alpha = 0.5f), cornerRadius = CornerRadius(size.height / 2),
        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
}.padding(horizontal = 8.dp, vertical = 2.dp), style = EyebrowStyle.copy(fontSize = 10.sp))

/** Bottom sheet (web `Sheet`): popover colour, rounded top, Fraunces title and a Close action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudioSheet(title: String, onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = StudioColors.Popover, contentColor = StudioColors.Foreground,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp), scrimColor = Color(0xB3110F0D),
        dragHandle = { Box(Modifier.padding(top = 12.dp).size(40.dp, 4.dp).clip(CircleShape).background(StudioColors.MutedForeground.copy(alpha = 0.4f))) }) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClose) { Text("Close", color = StudioColors.MutedForeground) }
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** One bottom-nav destination. */
class NavItem(val key: String, val label: String, val icon: ImageVector)

/** Web bottom navigation: 64dp, gold-soft pill behind the active icon, hairline on top. */
@Composable
fun StudioBottomNav(items: List<NavItem>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.background(StudioColors.Background.copy(alpha = 0.95f))) {
        HorizontalDivider(color = StudioColors.Border)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp)) {
            items.forEach { item ->
                val active = item.key == selected
                Column(Modifier.weight(1f).fillMaxHeight().clickable(role = Role.Tab) { onSelect(item.key) },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Box(Modifier.size(56.dp, 32.dp).clip(CircleShape).background(if (active) StudioColors.GoldSoft else Color.Transparent),
                        contentAlignment = Alignment.Center) {
                        Icon(item.icon, null, Modifier.size(20.dp), tint = if (active) StudioColors.Primary else StudioColors.MutedForeground)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(item.label, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                        color = if (active) StudioColors.Foreground else StudioColors.MutedForeground, maxLines = 1)
                }
            }
        }
    }
}

/** Round gold-gradient AI button that floats above the bottom nav (web `AssistantFab`). */
@Composable
fun AssistantFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(56.dp).shadow(16.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp)).background(StudioColors.GoldGradient)
        .clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Lucide.Sparkles, "Ask the AI Director", Modifier.size(22.dp), tint = StudioColors.PrimaryForeground)
    }
}

/** A one-line status message under the top bar (replaces the old secondary-container strip). */
@Composable
fun NoticeBar(text: String, onDismiss: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(StudioColors.Surface).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Lucide.Info, null, Modifier.size(16.dp), tint = StudioColors.Primary)
        Spacer(Modifier.width(10.dp))
        Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = StudioColors.SecondaryForeground)
        IconBtn(Lucide.X, "Dismiss", onDismiss)
    }
}

/** Underlined, horizontally scrolling tab row (the web project / library nav). */
@Composable
fun StudioTabs(tabs: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(StudioColors.Background)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
            tabs.forEach { t ->
                val active = t == selected
                Box(Modifier.heightIn(min = 48.dp).clickable(role = Role.Tab) { onSelect(t) }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center) {
                    Text(t, style = MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                        color = if (active) StudioColors.Primary else StudioColors.MutedForeground, maxLines = 1)
                    if (active) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).clip(CircleShape).background(StudioColors.Primary))
                }
            }
        }
        HorizontalDivider(color = StudioColors.Border)
    }
}

/** Plain search box in the field style. */
@Composable
fun StudioSearch(value: String, onValueChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    OutlinedTextField(value, onValueChange, modifier.fillMaxWidth(), singleLine = true,
        placeholder = { Text(placeholder, color = StudioColors.MutedForeground.copy(alpha = 0.7f)) },
        leadingIcon = { Icon(Lucide.Search, null, Modifier.size(18.dp), tint = StudioColors.MutedForeground) },
        textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), shape = RoundedCornerShape(12.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = StudioColors.Surface, unfocusedContainerColor = StudioColors.Surface,
            focusedBorderColor = StudioColors.Ring, unfocusedBorderColor = StudioColors.Input,
            cursorColor = StudioColors.Primary, focusedTextColor = StudioColors.Foreground, unfocusedTextColor = StudioColors.Foreground,
        ))
}
