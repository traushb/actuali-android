package io.github.traushb.actuali.views.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.traushb.actuali.services.L
import io.github.traushb.actuali.views.negative
import io.github.traushb.actuali.views.negativeContainer
import io.github.traushb.actuali.views.onNegativeContainer
import io.github.traushb.actuali.views.onPositiveContainer
import io.github.traushb.actuali.views.onWarningContainer
import io.github.traushb.actuali.views.positive
import io.github.traushb.actuali.views.positiveContainer
import io.github.traushb.actuali.views.warning
import io.github.traushb.actuali.views.warningContainer
import kotlinx.coroutines.delay

/**
 * The app's animation vocabulary, one duration per kind of change, so a
 * section collapsing in the budget table and one in the accounts list read as
 * the same gesture.
 */
object AppAnimation {
    /** Expanding or collapsing a section. */
    const val DISCLOSURE_MILLIS = 200

    /** A figure changing under the user — a budget edit, a transfer, a landed sync. */
    const val AMOUNT_MILLIS = 280

    /** Something appearing or disappearing in place: a banner, a toast. */
    const val APPEARANCE_MILLIS = 250

    /** How long a wait runs before a loading indicator shows. */
    const val LOADING_DELAY_MILLIS = 300L
}

/**
 * Rolls a currency figure to its new value instead of swapping it, so an edit
 * or a landed sync is visible where it landed. Keyed on the displayed string,
 * so masked balances cross-fade on the same curve when Hide Balances flips.
 *
 * At rest it's one plain Text: these sit in every budget and account row,
 * and AnimatedContent's per-instance transition machinery cost more than
 * the rows around it. The roll itself runs in graphics layers, so it
 * redraws rather than recomposing each frame.
 */
@Composable
fun AnimatedAmountText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    autoSize: TextAutoSize? = null,
) {
    var shown by remember { mutableStateOf(text) }
    var outgoing by remember { mutableStateOf<String?>(null) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(text) {
        if (text == shown) return@LaunchedEffect
        outgoing = shown
        shown = text
        progress.snapTo(0f)
        progress.animateTo(1f, tween(AppAnimation.AMOUNT_MILLIS))
        outgoing = null
    }
    val rolling = outgoing
    Layout(
        content = {
            if (rolling != null) {
                Text(
                    rolling,
                    color = color,
                    style = style,
                    autoSize = autoSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        translationY = -size.height / 2 * progress.value
                        alpha = 1 - progress.value
                    },
                )
            }
            Text(
                shown,
                color = color,
                style = style,
                autoSize = autoSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (rolling == null) Modifier else Modifier.graphicsLayer {
                    translationY = size.height / 2 * (1 - progress.value)
                    alpha = progress.value
                },
            )
        },
        // Clipped only while the two figures slide past each other.
        modifier = if (rolling == null) modifier else modifier.clipToBounds(),
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        val incoming = placeables.last()
        // Mid-roll the width eases from the old figure's to the new one's, as AnimatedContent's size transform did.
        val width = if (placeables.size == 1) incoming.width else lerp(placeables.first().width, incoming.width, progress.value)
        layout(width, placeables.maxOf { it.height }) { placeables.forEach { it.placeRelative(0, 0) } }
    }
}

/** Half opacity for a hidden row. alpha() always adds a graphics layer, so the rows that aren't hidden skip it. */
fun Modifier.dimmedWhen(hidden: Boolean): Modifier = if (hidden) alpha(0.5f) else this

/** The rotating chevron every collapsible section header uses: one symbol turned, not two swapped. */
@Composable
fun DisclosureChevron(isExpanded: Boolean, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (isExpanded) 90f else 0f, tween(AppAnimation.DISCLOSURE_MILLIS), label = "chevron")
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = modifier.size(18.dp).rotate(rotation))
}

/** Green over positive, red over negative, the default at exactly zero — shared by every balance so the copies don't drift. */
@Composable
fun balanceColor(balance: Long): Color = when {
    balance > 0 -> MaterialTheme.colorScheme.positive
    balance < 0 -> MaterialTheme.colorScheme.negative
    else -> LocalContentColor.current
}

/** What a figure says about the money behind it, which picks its colors: green, yellow, red or gray. */
enum class StatusTone {
    Positive,
    Warning,
    Negative,
    Neutral,
    ;

    companion object {
        /** A signed amount's tone: gray at zero, and while balances are hidden, so the fill gives nothing away. */
        fun of(amount: Long, masked: Boolean = false): StatusTone = when {
            masked || amount == 0L -> Neutral
            amount > 0 -> Positive
            else -> Negative
        }
    }
}

/** The tone as a solid fill, for pills and banners. */
val StatusTone.container: Color
    @Composable get() = when (this) {
        StatusTone.Positive -> MaterialTheme.colorScheme.positiveContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.warningContainer
        StatusTone.Negative -> MaterialTheme.colorScheme.negativeContainer
        StatusTone.Neutral -> MaterialTheme.colorScheme.surfaceContainerHighest
    }

/** Text and icons on [container]. */
val StatusTone.onContainer: Color
    @Composable get() = when (this) {
        StatusTone.Positive -> MaterialTheme.colorScheme.onPositiveContainer
        StatusTone.Warning -> MaterialTheme.colorScheme.onWarningContainer
        StatusTone.Negative -> MaterialTheme.colorScheme.onNegativeContainer
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** The tone as a plain color, for text, arrows and bars on the page. */
val StatusTone.color: Color
    @Composable get() = when (this) {
        StatusTone.Positive -> MaterialTheme.colorScheme.positive
        StatusTone.Warning -> MaterialTheme.colorScheme.warning
        StatusTone.Negative -> MaterialTheme.colorScheme.negative
        StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** An amount in a solid capsule of its tone: a balance you can act on. */
@Composable
fun AmountPill(
    text: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    autoSize: TextAutoSize? = null,
) {
    Box(modifier.background(tone.container, CircleShape).padding(contentPadding), contentAlignment = Alignment.Center) {
        AnimatedAmountText(text, color = tone.onContainer, style = style.copy(fontWeight = FontWeight.Bold), autoSize = autoSize)
    }
}

/** SwiftUI's ContentUnavailableView: an icon, a title and an explanation, centered in the space it has. */
@Composable
fun EmptyState(title: String, icon: ImageVector, description: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Expressive's empty state: the glyph large, on one of its shapes.
        Box(Modifier.size(112.dp).background(MaterialTheme.colorScheme.secondaryContainer, MaterialShapes.Cookie12Sided.toShape()), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(52.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Text(title, style = MaterialTheme.typography.titleLargeEmphasized, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 24.dp))
        description?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/**
 * Pull to refresh with Expressive's morphing loading indicator in place of the
 * spinner — every refreshable list's one gesture, so they all look alike.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshBox(isRefreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val state = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = { PullToRefreshDefaults.LoadingIndicator(state, isRefreshing, Modifier.align(Alignment.TopCenter)) },
        content = content,
    )
}

/** Centered spinner over a screen while the store loads. */
@Composable
fun LoadingOverlay(visible: Boolean) {
    if (!visible) return
    DelayedLoadingIndicator(Modifier.fillMaxSize())
}

/**
 * A loading indicator that only appears once the wait has lasted long enough
 * to notice. Most loads finish first, so nothing flashes, and the expressive
 * indicator's shape morphs — costly to set up — stay off quick paths like launch.
 */
@Composable
fun DelayedLoadingIndicator(modifier: Modifier = Modifier, contained: Boolean = false) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(AppAnimation.LOADING_DELAY_MILLIS)
        shown = true
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        if (shown) if (contained) ContainedLoadingIndicator() else LoadingIndicator()
    }
}

/**
 * Full-width notice pinned above the content while a budget's first sync is
 * running: until it lands, the figures come from the server snapshot, which
 * can be days behind (GH #126).
 */
@Composable
fun InitialSyncBanner(visible: Boolean) {
    AnimatedVisibility(visible, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .testTag("initialSyncBanner")
                        .semantics { contentDescription = L("Syncing. Amounts may not be up to date yet.") },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LoadingIndicator(Modifier.size(20.dp))
                    Text(L("Syncing — amounts may not be up to date yet"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

/** Transient capsule toast pinned to the top of the screen (e.g. "Posted 2 scheduled transactions"). */
@Composable
fun ToastView(text: String?, modifier: Modifier = Modifier) {
    AnimatedVisibility(text != null, modifier, enter = slideInVertically() + fadeIn(), exit = slideOutVertically() + fadeOut()) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, shadowElevation = 4.dp, modifier = Modifier.padding(top = 8.dp)) {
            Text(text.orEmpty(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

/** The scheme's three accent containers, which avatars and icons cycle through so a list reads in color without leaving the theme. */
enum class AccentRole {
    Primary,
    Secondary,
    Tertiary,
    ;

    companion object {
        /** The role [key] fixes, so a name keeps its color across launches. */
        fun of(key: String): AccentRole = entries[Math.floorMod(key.hashCode(), entries.size)]
    }
}

val AccentRole.container: Color
    @Composable get() = when (this) {
        AccentRole.Primary -> MaterialTheme.colorScheme.primaryContainer
        AccentRole.Secondary -> MaterialTheme.colorScheme.secondaryContainer
        AccentRole.Tertiary -> MaterialTheme.colorScheme.tertiaryContainer
    }

val AccentRole.onContainer: Color
    @Composable get() = when (this) {
        AccentRole.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
        AccentRole.Secondary -> MaterialTheme.colorScheme.onSecondaryContainer
        AccentRole.Tertiary -> MaterialTheme.colorScheme.onTertiaryContainer
    }

/**
 * Expressive's shapes for icon and avatar containers: the rounded, symmetric
 * ones, so a glyph or an initial sits centered in any of them.
 */
private val ContainerShapes = listOf(
    MaterialShapes.Cookie9Sided,
    MaterialShapes.Clover4Leaf,
    MaterialShapes.Sunny,
    MaterialShapes.Cookie6Sided,
    MaterialShapes.Gem,
    MaterialShapes.SoftBurst,
)

/** The container shape at [index], cycling through Expressive's rounded shapes. */
@Composable
fun containerShape(index: Int): Shape = ContainerShapes[Math.floorMod(index, ContainerShapes.size)].toShape()

/** An icon on [shape] in [role]'s container: settings rows, card headers, hero cards. */
@Composable
fun ShapedIcon(icon: ImageVector, role: AccentRole, shape: Shape, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(modifier.size(size).background(role.container, shape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = role.onContainer, modifier = Modifier.size(size * 0.55f))
    }
}

/**
 * A name's first letter in a tonal container — a list item's leading avatar —
 * its accent fixed by [key]. Dense lists keep the spec's circle; [shape] lets
 * a short list wear an Expressive one.
 */
@Composable
fun MonogramAvatar(name: String, key: String, modifier: Modifier = Modifier, shape: Shape = CircleShape) {
    val role = AccentRole.of(key)
    val initial = name.trim().firstOrNull()?.uppercase().orEmpty()
    // Decorative: the name it stands for is always read beside it.
    Box(modifier.size(40.dp).background(role.container, shape).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text(initial, style = MaterialTheme.typography.titleMediumEmphasized, color = role.onContainer)
    }
}
