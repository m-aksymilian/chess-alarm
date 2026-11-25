package app.chessalarm.ui

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs

private val ItemHeight = 44.dp
private const val VISIBLE = 5
private const val LOOPS = 400

/**
 * An iOS-style scrolling wheel: endless looping, snapping, items fade and tilt away from the
 * centre, with a tick of haptic feedback per step.
 */
@Composable
fun Wheel(
    items: List<String>,
    selected: Int,
    onSelected: (Int) -> Unit,
    width: Dp,
    loop: Boolean = true,
) {
    val count = if (loop) items.size * LOOPS else items.size
    val startIndex = if (loop) items.size * (LOOPS / 2) + selected else selected
    val state = rememberLazyListState(initialFirstVisibleItemIndex = startIndex)
    val fling = rememberSnapFlingBehavior(state)
    val view = LocalView.current
    val latestOnSelected by rememberUpdatedState(onSelected)
    val center by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            info.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - mid) }?.index ?: startIndex
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { center }.distinctUntilChanged().collect {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            if (!state.isScrollInProgress) latestOnSelected(it % items.size)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) latestOnSelected(center % items.size)
        }
    }
    LazyColumn(
        state = state,
        flingBehavior = fling,
        modifier = Modifier.width(width).height(ItemHeight * VISIBLE),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = ItemHeight * (VISIBLE / 2)),
    ) {
        items(count) { i ->
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            val item = info.visibleItemsInfo.firstOrNull { it.index == i }
            val dist = if (item != null) ((item.offset + item.size / 2f) - mid) / item.size else 2f
            val a = (1f - abs(dist) * 0.32f).coerceIn(0.15f, 1f)
            Box(
                Modifier.height(ItemHeight).fillMaxWidth().graphicsLayer {
                    alpha = a
                    rotationX = (-dist * 18f).coerceIn(-60f, 60f)
                    scaleX = 1f - abs(dist) * 0.06f
                    scaleY = 1f - abs(dist) * 0.06f
                },
                contentAlignment = Alignment.Center,
            ) {
                Text(items[i % items.size], style = Type.Body.copy(fontSize = 24.sp, fontWeight = FontWeight.Normal))
            }
        }
    }
}

/** Hour + minute wheels (+ AM/PM in 12-hour mode) with the iOS selection band. */
@Composable
fun TimeWheel(hour: Int, minute: Int, is24h: Boolean, onChange: (Int, Int) -> Unit) {
    val hours = if (is24h) (0..23).map { "%02d".format(it) } else (1..12).map { it.toString() }
    val minutes = (0..59).map { "%02d".format(it) }
    val pm = hour >= 12
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        // Selection band
        Box(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(ItemHeight)
                .clip(RoundedCornerShape(10.dp)).background(Palette.Grouped),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Wheel(
                hours,
                if (is24h) hour else (hour % 12).let { if (it == 0) 11 else it - 1 },
                { i ->
                    val h = if (is24h) i else ((i + 1) % 12) + if (pm) 12 else 0
                    onChange(h, minute)
                },
                84.dp,
            )
            Text(":", style = Type.Body.copy(fontSize = 24.sp))
            Wheel(minutes, minute, { onChange(hour, it) }, 84.dp)
            if (!is24h) {
                Wheel(listOf("AM", "PM"), if (pm) 1 else 0, { i ->
                    val base = hour % 12
                    onChange(if (i == 1) base + 12 else base, minute)
                }, 72.dp, loop = false)
            }
        }
        // Soft fade at the top and bottom edges.
        Box(
            Modifier.fillMaxWidth().height(ItemHeight * VISIBLE).background(
                Brush.verticalGradient(
                    0f to Palette.Background, 0.25f to Color.Transparent, 0.75f to Color.Transparent, 1f to Palette.Background,
                ),
            ),
        )
    }
}
