package com.shivansh.raasta.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shivansh.raasta.R

/**
 * Vays Maps colour rules:
 *  - Surfaces are neutral black/greys.
 *  - Accent = the violet "ink" ramp (#0A0224 … #CCC7F2), used sparingly: primary actions, the route, the road to take.
 *  - Green only for good news (offline ready, done, arrived). Red only for bad news (end, delete, off route, errors).
 */
/** Brand ramp: jet black → forest green → lime → soft ivory. (Kept the name `Ink` so call sites stay short.) */
object Ink {
    val i900 = Color(0xFF0D0D0D)   // jet black
    val i800 = Color(0xFF0F1A0A)
    val i700 = Color(0xFF143008)
    val i600 = Color(0xFF1A3F0C)
    val i500 = Color(0xFF224F10)   // forest green
    val i300 = Color(0xFFB0E454)   // lime green
    val i100 = Color(0xFFEAFFD0)   // soft ivory
}

object C {
    // Jet black surfaces, soft-ivory text, lime as the one accent. Lime doubles as "good"; red stays "bad".
    val bg = Color(0xFF0D0D0D)
    val grouped = Color(0xFF161716)       // cards
    val elevated = Color(0xFF1F201E)      // controls on cards
    val fill = Color(0xFF2A2C28)
    val separator = Color(0xFF242622)
    val keyline = Color(0xFF5E6458)       // outlines of roads you should NOT take
    val label = Color(0xFFF3FCE6)         // soft ivory, eased toward white for body text
    val secondary = Color(0xFF8E9886)
    val tertiary = Color(0xFF565C52)
    val blue = Ink.i300                   // the accent: lime (name kept so call sites stay short)
    val blueLight = Ink.i100              // accent text / icons on dark: soft ivory
    val ice = Ink.i100
    val blueDeep = Ink.i500               // forest green
    val onAccent = Ink.i900               // jet black text on lime
    val good = Ink.i300
    val red = Color(0xFFFF4D4D)
    val glassTint = Color(0xC00D0D0D)
}

/** The glossy capsule, in ink: light lavender at the top, deep indigo at the bottom. */
object Gloss {
    val fill = Brush.verticalGradient(listOf(Color(0xFFC6EE7E), Ink.i300))
    val shine = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.34f), 0.5f to Color.White.copy(alpha = 0.04f), 1f to Color.Transparent)
    val road = Brush.verticalGradient(listOf(Ink.i100, Ink.i300, Color(0xFF8FCB2F)))
    val card = Brush.verticalGradient(listOf(Ink.i700, Ink.i900))
}

private fun inter(weight: Int) = Font(
    R.font.inter, FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Inter: the closest open font to SF Pro. */
val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700), inter(800), inter(900))

object T {
    val largeTitle = TextStyle(fontFamily = Inter, fontSize = 34.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp, color = C.label)
    val title1 = TextStyle(fontFamily = Inter, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, color = C.label)
    val title2 = TextStyle(fontFamily = Inter, fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp, color = C.label)
    val title3 = TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = C.label)
    val headline = TextStyle(fontFamily = Inter, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp, color = C.label)
    val body = TextStyle(fontFamily = Inter, fontSize = 17.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.3).sp, color = C.label)
    val subhead = TextStyle(fontFamily = Inter, fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = (-0.15).sp, color = C.secondary)
    val footnote = TextStyle(fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Normal, color = C.secondary)
    val caption = TextStyle(fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = C.secondary)
    /** Small uppercase metadata ("11 MIN · 6.9 KM"), wide tracking, tabular figures. */
    val label = TextStyle(fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.3.sp, color = C.secondary, fontFeatureSettings = "tnum")
    /** Editorial display line: big and light. */
    val display = TextStyle(fontFamily = Inter, fontSize = 38.sp, fontWeight = FontWeight.Light, letterSpacing = (-1.2).sp, lineHeight = 42.sp, color = C.label)
    val displayBold = display.copy(fontWeight = FontWeight.Bold)
    val number = TextStyle(fontFamily = Inter, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1.6).sp, color = C.label, fontFeatureSettings = "tnum")
}

@Composable
fun RaastaTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.blue, onPrimary = Color.White, secondary = C.blueLight,
            background = C.bg, onBackground = C.label, surface = C.grouped, onSurface = C.label,
            surfaceVariant = C.elevated, onSurfaceVariant = C.secondary, outline = C.separator, error = C.red,
        ),
        typography = Typography(
            bodyLarge = base.bodyLarge.copy(fontFamily = Inter),
            bodyMedium = base.bodyMedium.copy(fontFamily = Inter),
            labelLarge = base.labelLarge.copy(fontFamily = Inter),
            titleLarge = base.titleLarge.copy(fontFamily = Inter),
            titleMedium = base.titleMedium.copy(fontFamily = Inter),
        ),
        content = content,
    )
}

// ---------------------------------------------------------------- panels

/**
 * Solid dark panel for things floating over the map. No live blur: it cost a full re-render of the
 * moving map every frame, and a solid panel also reads better in sunlight.
 */
fun Modifier.panel(shape: Shape, color: Color = C.grouped): Modifier =
    this.clip(shape).background(color).border(1.dp, Color.White.copy(alpha = 0.06f), shape)

// ---------------------------------------------------------------- components

/** Press feedback like UIKit: a quick spring squeeze and a slight dim, no ripple. */
@Composable
fun Modifier.pressable(onClick: () -> Unit, enabled: Boolean = true): Modifier {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val k by androidx.compose.animation.core.animateFloatAsState(
        if (pressed) 0.96f else 1f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 900f), label = "press",
    )
    return this.graphicsLayer { scaleX = k; scaleY = k; alpha = 0.7f + 0.3f * ((k - 0.96f) / 0.04f) }
        .clickable(src, null, enabled = enabled, onClick = onClick)
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = T.footnote.copy(letterSpacing = 0.4.sp), modifier = modifier.padding(start = 16.dp, bottom = 8.dp, top = 24.dp))
}

/** Inset grouped list section: near-black with a hairline blue rim. */
@Composable
fun Grouped(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(C.grouped), content = content)
}

@Composable
fun Separator(inset: Dp = 16.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(C.separator))
}

@Composable
fun ListRow(
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) { leading(); Box(Modifier.width(14.dp)) }
        Column(Modifier.weight(1f), content = content)
        if (trailing != null) Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

/** Keyline icon: outlined symbol in a thin blue ring. */
@Composable
fun KeyIcon(icon: ImageVector, size: Dp = 40.dp, tint: Color = C.label) {
    Box(
        Modifier.size(size).clip(CircleShape).background(C.elevated),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.52f)) }
}

/** Primary action: the glossy blue capsule. */
@Composable
fun GlossCapsule(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    height: Dp = 56.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier.height(height).clip(CircleShape)
            .background(if (enabled) Gloss.fill else Brush.verticalGradient(listOf(C.elevated, C.elevated)))
            .pressable(onClick, enabled),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.padding(horizontal = 20.dp)) {
            val c = if (enabled) C.onAccent else C.tertiary
            if (icon != null) { Icon(icon, null, tint = c, modifier = Modifier.size(22.dp)); Box(Modifier.width(8.dp)) }
            Text(text, style = T.headline.copy(color = c, fontWeight = FontWeight.Bold))
        }
    }
}

/** Secondary action: dark capsule with a keyline. */
@Composable
fun LineCapsule(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, height: Dp = 56.dp, tint: Color = C.label, onClick: () -> Unit) {
    Row(
        modifier.height(height).clip(CircleShape).background(C.elevated).border(1.dp, if (tint == C.label) C.separator else tint.copy(alpha = 0.45f), CircleShape)
            .pressable(onClick).padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp)); Box(Modifier.width(8.dp)) }
        Text(text, style = T.headline.copy(color = tint))
    }
}

@Composable
fun GlassCircle(icon: ImageVector, description: String, tint: Color = C.label, size: Dp = 52.dp, onClick: () -> Unit) {
    Box(Modifier.size(size).panel(CircleShape).pressable(onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun Grabber() {
    Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 38.dp, height = 5.dp).clip(CircleShape).background(C.tertiary))
    }
}

@Composable
fun Pill(text: String, color: Color = C.blueLight, modifier: Modifier = Modifier) {
    Text(
        text, style = T.caption.copy(color = color, fontWeight = FontWeight.SemiBold),
        modifier = modifier.clip(CircleShape).background(color.copy(alpha = 0.14f))
            .padding(horizontal = 9.dp, vertical = 3.dp),
    )
}

/** Brand mark: a small glossy blue pill. */
@Composable
fun BrandPill(modifier: Modifier = Modifier) {
    Box(modifier.size(width = 30.dp, height = 16.dp).clip(CircleShape).background(Gloss.fill).border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape)) {
        Box(Modifier.fillMaxSize().padding(horizontal = 3.dp, vertical = 1.dp).clip(CircleShape).background(Gloss.shine))
    }
}
