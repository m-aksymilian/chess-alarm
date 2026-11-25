package app.chessalarm.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon

object Palette {
    val Background = Color(0xFF000000)
    val Grouped = Color(0xFF1C1C1E)
    val Elevated = Color(0xFF2C2C2E)
    val Separator = Color(0xFF38383A)
    val Text = Color(0xFFFFFFFF)
    val Secondary = Color(0xFF8E8E93)
    val Tertiary = Color(0xFF48484A)
    val Accent = Color(0xFFFF9F0A)
    val Danger = Color(0xFFFF453A)
    val SwitchOff = Color(0xFF39393D)
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.Black,
    secondary = Palette.Accent,
    background = Palette.Background,
    onBackground = Palette.Text,
    surface = Palette.Grouped,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Elevated,
    onSurfaceVariant = Palette.Secondary,
    surfaceContainer = Palette.Grouped,
    surfaceContainerHigh = Palette.Elevated,
    surfaceContainerHighest = Palette.Elevated,
    outline = Palette.Separator,
    error = Palette.Danger,
)

@Composable
fun ChessAlarmTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

object Type {
    val LargeTitle = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Palette.Text, letterSpacing = 0.2.sp)
    val Title = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text)
    val Body = TextStyle(fontSize = 17.sp, color = Palette.Text)
    val Secondary = TextStyle(fontSize = 15.sp, color = Palette.Secondary)
    val Footnote = TextStyle(fontSize = 13.sp, color = Palette.Secondary)
    val SectionHeader = TextStyle(fontSize = 13.sp, color = Palette.Secondary, letterSpacing = 0.4.sp)
    val BigTime = TextStyle(fontSize = 60.sp, fontWeight = FontWeight.Thin, color = Palette.Text, letterSpacing = (-1).sp)
}

/** A large, rounded iOS-style switch with a springy thumb. */
@Composable
fun IosSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val track by animateColorAsState(if (checked) Palette.Accent else Palette.SwitchOff, label = "track")
    val offset by animateDpAsState(
        if (checked) 20.dp else 0.dp,
        spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "thumb",
    )
    Box(
        Modifier
            .size(51.dp, 31.dp)
            .clip(CircleShape)
            .background(if (enabled) track else track.copy(alpha = 0.4f))
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onCheckedChange(!checked) }
            .padding(2.dp),
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(27.dp)
                .shadow(3.dp, CircleShape)
                .clip(CircleShape)
                .background(Color.White),
        )
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        style = Type.SectionHeader,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 24.dp, bottom = 7.dp),
    )
}

@Composable
fun SectionFooter(text: String) {
    Text(text, style = Type.Footnote, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 7.dp))
}

/** An inset grouped list section, as in iOS Settings. Separators are added between children by [GroupRow]. */
@Composable
fun Group(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (header != null) SectionHeader(header) else Spacer(Modifier.height(24.dp))
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Palette.Grouped),
        content = content,
    )
    if (footer != null) SectionFooter(footer)
}

@Composable
fun GroupDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = Palette.Separator)
}

@Composable
fun GroupRow(
    title: String,
    value: String? = null,
    subtitle: String? = null,
    titleColor: Color = Palette.Text,
    divider: Boolean = true,
    chevron: Boolean = false,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = Palette.Accent, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = Type.Body.copy(color = titleColor))
                if (subtitle != null) Text(subtitle, style = Type.Footnote)
            }
            if (value != null) {
                Text(value, style = Type.Body.copy(color = Palette.Secondary), maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (chevron) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.ChevronRight, null, tint = Palette.Tertiary, modifier = Modifier.size(14.dp))
            }
        }
        if (divider) GroupDivider()
    }
}

@Composable
fun SwitchRow(title: String, checked: Boolean, subtitle: String? = null, divider: Boolean = true, onChange: (Boolean) -> Unit) {
    GroupRow(title, subtitle = subtitle, divider = divider, trailing = { IosSwitch(checked, onChange) })
}

@Composable
fun SliderRow(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    steps: Int = 0,
    divider: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.Body, modifier = Modifier.weight(1f))
            Text(valueLabel, style = Type.Body.copy(color = Palette.Secondary))
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Palette.Accent,
                inactiveTrackColor = Palette.SwitchOff,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
    }
    if (divider) GroupDivider()
}

/** iOS-style segmented choice row: tap cycles through options in a menu-like chip row. */
@Composable
fun <T> ChoiceRow(title: String, options: List<T>, selected: T, label: (T) -> String, divider: Boolean = true, onSelect: (T) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(title, style = Type.Body)
        Spacer(Modifier.height(8.dp))
        FlowChips(options, selected, label, onSelect)
    }
    if (divider) GroupDivider()
}

@Composable
fun <T> FlowChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { o ->
            val sel = o == selected
            val bg by animateColorAsState(if (sel) Palette.Accent else Palette.Elevated, label = "chip")
            Text(
                label(o),
                style = Type.Secondary.copy(color = if (sel) Color.Black else Palette.Text, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal),
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onSelect(o) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

/** Rounded pill button. */
@Composable
fun PillButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    color: Color = if (primary) Palette.Accent else Palette.Elevated,
    textColor: Color = if (primary) Color.Black else Palette.Text,
    onClick: () -> Unit,
) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = Type.Title.copy(color = textColor))
    }
}

/** Top bar for pushed screens: back/cancel on the left, title, optional action on the right. */
@Composable
fun NavBar(title: String, left: String? = "Back", onLeft: (() -> Unit)? = null, right: String? = null, onRight: (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 8.dp)) {
        if (left != null && onLeft != null) {
            Row(
                Modifier.align(Alignment.CenterStart).clip(RoundedCornerShape(8.dp)).clickable(onClick = onLeft).padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (left == "Back") Icon(Icons.ChevronLeft, null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
                Text(left, style = Type.Body.copy(color = Palette.Accent))
            }
        }
        Text(title, style = Type.Title, modifier = Modifier.align(Alignment.Center))
        if (right != null && onRight != null) {
            Text(
                right,
                style = Type.Body.copy(color = Palette.Accent, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.align(Alignment.CenterEnd).clip(RoundedCornerShape(8.dp)).clickable(onClick = onRight).padding(8.dp),
            )
        }
    }
}
