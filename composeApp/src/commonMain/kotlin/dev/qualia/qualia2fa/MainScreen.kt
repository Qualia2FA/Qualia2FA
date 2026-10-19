package dev.qualia.qualia2fa

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.qualia.qualia2fa.resources.Res
import dev.qualia.qualia2fa.resources.icon_2fa
import dev.qualia.qualia2fa.resources.icon_settings
import dev.qualia.qualia2fa.resources.icon_sync
import dev.qualia.qualia2fa.resources.nav_settings
import dev.qualia.qualia2fa.resources.nav_sync
import dev.qualia.qualia2fa.resources.nav_two_factor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/** The three screens the main navigation switches between. */
enum class Tab {
    /** The entries and their codes. */
    TwoFactor,

    /** Exchanging codes with another device. */
    Sync,

    /** Preferences and everything about this device. */
    Settings,
}

/** A tab's label and icon. */
private data class TabAppearance(val label: StringResource, val icon: androidx.compose.ui.graphics.painter.Painter)

/**
 * How much room the navigation island covers at the end of a screen.
 *
 * Screens with their own scrolling read this to stop their last item above the island. A screen
 * that does not scroll — the login and loading screens, which have no navigation — gets `0.dp`.
 * The middle of a scrolling screen is allowed to pass under the island; only the end has to be
 * reachable, which is what this inset buys.
 */
val LocalNavigationInset = staticCompositionLocalOf { 0.dp }

/** The corner radius of the island itself. */
private val IslandCorner = 30.dp

/** The gap between the island and the window edge. */
private val IslandMargin = 12.dp

/** How wide the vertical island is. */
private val SideIslandWidth = 88.dp

/** How wide the island may become at the bottom of a wide window. */
private val BottomIslandMaxWidth = 420.dp

/** The height of one item in the island. */
private val IslandItemHeight = 52.dp

/** How much room a screen has to leave for the bottom island: its own height plus its margin. */
private val BottomNavigationInset: Dp = IslandItemHeight + Space.s * 2f + IslandMargin + Space.s

/**
 * The main screens' navigation: an island, not an edge-to-edge bar.
 *
 * # What "island" means here
 *
 * The navigation is one rounded surface with its own margin, rather than a strip attached to the
 * window edge. In a landscape window it stands on the left, with the content laid out beside it;
 * in a portrait window it floats over the bottom of the content. The side arrangement reserves
 * the island's width, so the content is never behind it. The bottom arrangement deliberately lets
 * the content pass under the island — the picture in the design shows exactly that — and every
 * scrollable screen adds [LocalNavigationInset] at its end, so the last row stops above it. A
 * list is therefore never unreadable at its end, while the space the island covers is still used
 * while the user scrolls.
 *
 * # Why not Material's navigation components
 *
 * `NavigationRail` and `NavigationBar` own their container: a rail fills the window's height and
 * a bar spans its width, with the selected item's indicator positioned by the component. Both
 * shapes are the opposite of an island, and neither can be inset from the edge without drawing a
 * second background around it. The item here is one rounded surface with one icon and one label,
 * so the selected color cannot come from two different places.
 */
@Composable
fun MainScreen(
    tab: Tab,
    onSelectTab: (Tab) -> Unit,
    content: @Composable () -> Unit,
) {
    val items =
        mapOf(
            Tab.TwoFactor to TabAppearance(Res.string.nav_two_factor, painterResource(Res.drawable.icon_2fa)),
            Tab.Sync to TabAppearance(Res.string.nav_sync, painterResource(Res.drawable.icon_sync)),
            Tab.Settings to TabAppearance(Res.string.nav_settings, painterResource(Res.drawable.icon_settings)),
        )

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val side = navigationPlacement(maxWidth, maxHeight) == NavigationPlacement.Side

        // The content always lives in the same Row slot. Reserving the side island's room with
        // a zero-width gutter instead of switching between a Row and a bare Box is what keeps a
        // tab's state alive when the island's placement — or the window's size — changes.
        CompositionLocalProvider(
            LocalNavigationInset provides if (side) 0.dp else BottomNavigationInset,
        ) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(Modifier.width(if (side) SideIslandWidth + IslandMargin * 2 else 0.dp))
                Box(modifier = Modifier.weight(1f)) { content() }
            }
        }

        if (side) {
            NavigationIsland(
                items = items,
                selected = tab,
                onSelect = onSelectTab,
                vertical = true,
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(start = IslandMargin, top = Space.xl)
                        .width(SideIslandWidth),
            )
        } else {
            NavigationIsland(
                items = items,
                selected = tab,
                onSelect = onSelectTab,
                vertical = false,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = Space.l, vertical = IslandMargin)
                        .widthIn(max = BottomIslandMaxWidth)
                        .fillMaxWidth(),
            )
        }
    }
}

/** The island: a rounded surface holding the tabs, in a column or in a row. */
@Composable
private fun NavigationIsland(
    items: Map<Tab, TabAppearance>,
    selected: Tab,
    onSelect: (Tab) -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    ShadowedSurface(shape = RoundedCornerShape(IslandCorner), modifier = modifier) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(IslandCorner),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 0.dp,
        ) {
            if (vertical) {
            Column(
                modifier = Modifier.padding(Space.s).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Tab.entries.forEach { entry ->
                    IslandTab(
                        appearance = items.getValue(entry),
                        selected = entry == selected,
                        onClick = { onSelect(entry) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            } else {
                Row(
                    modifier = Modifier.padding(Space.s).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    Tab.entries.forEach { entry ->
                        IslandTab(
                            appearance = items.getValue(entry),
                            selected = entry == selected,
                            onClick = { onSelect(entry) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One tab of the island: an icon over its label, on one rounded surface. */
@Composable
private fun IslandTab(
    appearance: TabAppearance,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background =
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            hovered ->
                lerp(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.primary,
                    NAV_HOVER_TINT,
                )
            else -> Color.Transparent
        }
    val content = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier =
            modifier
                .height(IslandItemHeight)
                .clip(RoundedCornerShape(IslandItemHeight / 2f))
                .background(background)
                .hoverable(interaction)
                .selectable(
                    selected = selected,
                    role = Role.Tab,
                    interactionSource = interaction,
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = Space.s),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                painter = appearance.icon,
                contentDescription = null,
                modifier = Modifier.size(Sizes.navIcon),
                tint = content,
            )
            Text(
                text = stringResource(appearance.label),
                style = MaterialTheme.typography.labelSmall,
                color = content,
                maxLines = 1,
            )
        }
    }
}

/** How far an unselected tab moves towards the ink while the pointer is over it. */
private const val NAV_HOVER_TINT = 0.08f
