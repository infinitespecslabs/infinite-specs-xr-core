/*
 * SpatialPanelComposable.kt
 * infinite-specs-xr-core — UI Layer (Perception / Display)
 *
 * Glanceable heads-up display panel rendered in the developer's field of
 * view using the androidx.xr.compose (Jetpack XR / Glimmer) Subspace APIs.
 *
 * Android XR Developer Preview 4
 * See: https://developer.android.com/xr/compose
 *
 * ┌─────────────────────────────────────────────────────────────────────────┐
 * │  SPATIAL PANEL LAYOUT                                                    │
 * │                                                                          │
 * │  ┌──────────────────────────────────────────────┐                       │
 * │  │  Infinite Specs         [●] STREAMING        │   ← SpatialPanel     │
 * │  │  Spatial Architecture Monitor                │                       │
 * │  │──────────────────────────────────────────────│                       │
 * │  │  ┌────────────┐  ┌────────────┐              │                       │
 * │  │  │ api-gateway│  │  auth-svc  │   ...        │   ← NodeCards        │
 * │  │  │ INSPECT 92%│  │ SPECIFY 88%│              │                       │
 * │  │  └────────────┘  └────────────┘              │                       │
 * │  │──────────────────────────────────────────────│                       │
 * │  │  ▶ Streaming spec to IDE…                    │   ← Status bar       │
 * │  └──────────────────────────────────────────────┘                       │
 * └─────────────────────────────────────────────────────────────────────────┘
 */

package com.infinitespecs.xr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinitespecs.xr.perception.IntentType

// ─────────────────────────────────────────────────────────────────────────────
// Data models for the UI layer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable view-model snapshot representing a single architecture node card
 * rendered inside the spatial heads-up display.
 *
 * @param nodeId          Stable node identifier (e.g., "api-gateway").
 * @param label           Human-readable display label.
 * @param intentType      Most recently resolved [IntentType] for this node.
 * @param confidencePct   Confidence expressed as an integer percentage [0, 100].
 * @param isActive        Whether this node is currently under gaze focus.
 */
@Stable
data class NodeCardState(
    val nodeId: String,
    val label: String,
    val intentType: IntentType = IntentType.UNKNOWN,
    val confidencePct: Int = 0,
    val isActive: Boolean = false,
)

/**
 * Overall panel status communicated to the developer at a glance.
 */
enum class PanelStatus {
    IDLE,
    PROCESSING,
    STREAMING,
}

// ─────────────────────────────────────────────────────────────────────────────
// Spatial panel composables
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Root spatial composable for the Infinite Specs heads-up display.
 *
 * ## Android XR Integration (Developer Preview 4)
 *
 * In a real XR session this composable would be wrapped inside a
 * `androidx.xr.compose.SubspaceComposable` and hosted by a `SpatialPanel`
 * to float in the developer's field of view above the physical environment.
 *
 * ```kotlin
 * // In an XR-capable Activity / Fragment:
 * Subspace {
 *     SpatialPanel(
 *         modifier = SubspaceModifier
 *             .width(600.dp)
 *             .height(300.dp)
 *             .offset(x = 0.dp, y = 0.dp, z = -0.8f),  // 80 cm in front
 *     ) {
 *         InfiniteSpecsHudPanel(nodes = nodes, status = status)
 *     }
 * }
 * ```
 *
 * On standard Android (no XR runtime) the composable renders as a regular
 * on-screen card — suitable for Compose Preview and JVM screenshot tests.
 *
 * @param nodes   Ordered list of [NodeCardState] items to display.
 * @param status  Current [PanelStatus] shown in the header and status bar.
 * @param modifier Optional [Modifier] for size / positioning from the parent.
 */
@Composable
fun InfiniteSpecsHudPanel(
    nodes: List<NodeCardState>,
    status: PanelStatus = PanelStatus.IDLE,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp)),
        color = HudColors.PanelBackground,
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            HudPanelHeader(status = status)
            Spacer(modifier = Modifier.height(12.dp))
            NodeCardRow(nodes = nodes)
            Spacer(modifier = Modifier.height(12.dp))
            HudStatusBar(status = status)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Panel header showing the app title and streaming indicator badge.
 */
@Composable
private fun HudPanelHeader(status: PanelStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Infinite Specs",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = HudColors.TextPrimary,
            )
            Text(
                text = "Spatial Architecture Monitor",
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = HudColors.TextSecondary,
            )
        }
        StreamingBadge(status = status)
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Small indicator badge showing whether the MCP bridge is actively streaming.
 */
@Composable
private fun StreamingBadge(status: PanelStatus) {
    val (badgeColor, badgeText) = when (status) {
        PanelStatus.STREAMING   -> HudColors.AccentGreen  to "● STREAMING"
        PanelStatus.PROCESSING  -> HudColors.AccentAmber  to "◌ PROCESSING"
        PanelStatus.IDLE        -> HudColors.TextDisabled to "○ IDLE"
    }
    Box(
        modifier = Modifier
            .background(
                color = badgeColor.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp),
            )
            .border(
                width = 1.dp,
                color = badgeColor.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = badgeText,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            color = badgeColor,
            fontWeight = FontWeight.Bold,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Horizontally scrollable row of [NodeCard] items.
 */
@Composable
private fun NodeCardRow(nodes: List<NodeCardState>) {
    if (nodes.isEmpty()) {
        Text(
            text = "No architecture nodes detected.",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = HudColors.TextDisabled,
        )
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(nodes, key = { it.nodeId }) { node ->
            NodeCard(state = node)
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Individual architecture node card.
 *
 * Displays the node label, resolved [IntentType], and confidence percentage.
 * Active (gaze-focused) nodes are highlighted with an accent border.
 *
 * @param state Current [NodeCardState] snapshot for this node.
 */
@Composable
fun NodeCard(
    state: NodeCardState,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (state.isActive) HudColors.AccentBlue else Color.Transparent
    val cardBackground = if (state.isActive)
        HudColors.CardBackgroundActive else HudColors.CardBackground

    Box(
        modifier = modifier
            .width(120.dp)
            .background(color = cardBackground, shape = RoundedCornerShape(8.dp))
            .border(width = 1.5.dp, color = borderColor, shape = RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Column {
            Text(
                text = state.label,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = HudColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = state.intentType.name,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = intentTypeColor(state.intentType),
            )
            Text(
                text = "${state.confidencePct}%",
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = HudColors.TextSecondary,
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bottom status bar displaying the current pipeline state to the developer.
 */
@Composable
private fun HudStatusBar(status: PanelStatus) {
    val statusText = when (status) {
        PanelStatus.IDLE        -> "Awaiting spatial intent…"
        PanelStatus.PROCESSING  -> "Translating spatial intent…"
        PanelStatus.STREAMING   -> "▶ Streaming spec to IDE…"
    }
    val statusColor = when (status) {
        PanelStatus.STREAMING   -> HudColors.AccentGreen
        PanelStatus.PROCESSING  -> HudColors.AccentAmber
        PanelStatus.IDLE        -> HudColors.TextDisabled
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = HudColors.StatusBarBackground,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = statusText,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = statusColor,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Colour palette (dark HUD theme)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * HUD colour tokens.
 *
 * Designed for maximum legibility on see-through ambient XR glass displays
 * with high ambient light conditions. All colours are intentionally high-
 * contrast against both dark and light physical backgrounds.
 */
private object HudColors {
    val PanelBackground      = Color(0xCC0D1117)  // near-black, 80% opacity
    val CardBackground       = Color(0xFF161B22)
    val CardBackgroundActive = Color(0xFF1F3A5F)
    val StatusBarBackground  = Color(0xFF0D1117)
    val TextPrimary          = Color(0xFFE6EDF3)
    val TextSecondary        = Color(0xFF8B949E)
    val TextDisabled         = Color(0xFF484F58)
    val AccentBlue           = Color(0xFF58A6FF)
    val AccentGreen          = Color(0xFF3FB950)
    val AccentAmber          = Color(0xFFD29922)
}

/**
 * Returns a colour appropriate for the given [IntentType] badge.
 */
private fun intentTypeColor(intentType: IntentType): Color = when (intentType) {
    IntentType.INSPECT  -> Color(0xFF58A6FF)  // blue
    IntentType.SPECIFY  -> Color(0xFF3FB950)  // green
    IntentType.EXPAND   -> Color(0xFFD29922)  // amber
    IntentType.CONNECT  -> Color(0xFFBC8CFF)  // purple
    IntentType.UNKNOWN  -> Color(0xFF8B949E)  // muted grey
}

// ─────────────────────────────────────────────────────────────────────────────
// Compose Previews
// ─────────────────────────────────────────────────────────────────────────────

@Preview(name = "HUD Panel — Streaming", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewHudPanelStreaming() {
    MaterialTheme {
        InfiniteSpecsHudPanel(
            nodes = listOf(
                NodeCardState("api-gateway",  "api-gateway",   IntentType.SPECIFY,  92, isActive = true),
                NodeCardState("auth-service", "auth-service",  IntentType.INSPECT,  88),
                NodeCardState("data-store",   "data-store",    IntentType.CONNECT,  75),
                NodeCardState("event-bus",    "event-bus",     IntentType.EXPAND,   61),
            ),
            status = PanelStatus.STREAMING,
        )
    }
}

@Preview(name = "HUD Panel — Idle (empty)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewHudPanelIdle() {
    MaterialTheme {
        InfiniteSpecsHudPanel(nodes = emptyList(), status = PanelStatus.IDLE)
    }
}
