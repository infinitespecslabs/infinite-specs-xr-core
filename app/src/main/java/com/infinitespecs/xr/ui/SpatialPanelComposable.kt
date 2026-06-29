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
 * │  │  │ AsyncBridge│  │ KafkaCons  │              │                       │
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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

// ─────────────────────────────────────────────────────────────────────────────
// Data models for the UI layer
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Immutable view-model snapshot representing a single architecture node card
 * rendered inside the spatial heads-up display.
 *
 * @param nodeId                       Stable node identifier (e.g., "api-gateway").
 * @param label                        Human-readable display label.
 * @param nodeType                     The node classifier (e.g. "AsynchronousEventBridge").
 * @param physicalAnchorId             Target real-world object vector link identifier.
 * @param semanticConstraints          List of system invariants parsed from user context.
 * @param loopEngineeringSkillTemplate Target background sub-agent execution flow.
 * @param isActive                     Whether this node is currently under gaze focus.
 */
@Stable
data class NodeCardState(
    val nodeId: String,
    val label: String,
    val nodeType: String = "",
    val physicalAnchorId: String = "",
    val semanticConstraints: List<String> = emptyList(),
    val loopEngineeringSkillTemplate: String = "",
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
 * On standard Android (no XR runtime) the composable renders as a regular
 * on-screen card — suitable for Compose Preview and JVM screenshot tests.
 */
@Composable
fun InfiniteSpecsHudPanel(
    modifier: Modifier = Modifier,
    nodes: List<NodeCardState>,
    status: PanelStatus = PanelStatus.IDLE,
    logs: List<String> = emptyList(),
    onTrigger: () -> Unit = {},
) {
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp)),
            color = HudColors.PanelBackground,
            tonalElevation = 4.dp,
            shadowElevation = 8.dp,
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                HudPanelHeader(status = status)
                Spacer(modifier = Modifier.height(12.dp))
                NodeCardRow(nodes = nodes)
                if (logs.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HudLogArea(logs = logs)
                }
                Spacer(modifier = Modifier.height(12.dp))
                HudStatusBar(status = status)
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onTrigger,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = HudColors.AccentBlue),
                    enabled = status == PanelStatus.IDLE,
                ) {
                    Text(
                        text = "Engage Perception Pipeline",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color.White,
                    )
                }
            }
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
                text = "Loop Engineering research sandbox",
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
        PanelStatus.STREAMING -> HudColors.AccentGreen to "● STREAMING"
        PanelStatus.PROCESSING -> HudColors.AccentAmber to "◌ PROCESSING"
        PanelStatus.IDLE -> HudColors.TextDisabled to "○ IDLE"
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
 * Displays the node label, resolved node type, target skill template, and anchor ID.
 */
@Composable
fun NodeCard(
    state: NodeCardState,
    modifier: Modifier = Modifier,
) {
    val borderColor = if (state.isActive) HudColors.AccentBlue else Color.Transparent
    val cardBackground = if (state.isActive) {
        HudColors.CardBackgroundActive
    } else {
        HudColors.CardBackground
    }

    Box(
        modifier = modifier
            .width(160.dp)
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
            if (state.nodeType.isNotEmpty()) {
                Text(
                    text = state.nodeType,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = nodeTypeColor(state.nodeType),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.physicalAnchorId.isNotEmpty()) {
                Text(
                    text = "@ ${state.physicalAnchorId}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = HudColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (state.loopEngineeringSkillTemplate.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Skill: ${state.loopEngineeringSkillTemplate}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 8.sp,
                    color = HudColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Area displaying real-time logs from external autonomous agents.
 */
@Composable
private fun HudLogArea(logs: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(8.dp),
    ) {
        logs.forEach { log ->
            Text(
                text = "> $log",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = HudColors.AccentGreen.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
        PanelStatus.IDLE -> "Awaiting spatial telemetry..."
        PanelStatus.PROCESSING -> "Folding telemetry into MCP schema..."
        PanelStatus.STREAMING -> "▶ Streaming specs to autonomous IDE loops..."
    }
    val statusColor = when (status) {
        PanelStatus.STREAMING -> HudColors.AccentGreen
        PanelStatus.PROCESSING -> HudColors.AccentAmber
        PanelStatus.IDLE -> HudColors.TextDisabled
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

private object HudColors {
    val PanelBackground = Color(0xCC0D1117) // near-black, 80% opacity
    val CardBackground = Color(0xFF161B22)
    val CardBackgroundActive = Color(0xFF1F3A5F)
    val StatusBarBackground = Color(0xFF0D1117)
    val TextPrimary = Color(0xFFE6EDF3)
    val TextSecondary = Color(0xFF8B949E)
    val TextDisabled = Color(0xFF484F58)
    val AccentBlue = Color(0xFF58A6FF)
    val AccentGreen = Color(0xFF3FB950)
    val AccentAmber = Color(0xFFD29922)
}

/**
 * Returns a color appropriate for the given nodeType.
 */
private fun nodeTypeColor(nodeType: String): Color = when (nodeType) {
    "AsynchronousEventBridge" -> Color(0xFFBC8CFF) // purple
    "KafkaConsumer" -> Color(0xFF58A6FF) // blue
    "DMXLightingController" -> Color(0xFF3FB950) // green
    else -> Color(0xFFD29922) // amber
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
                NodeCardState(
                    nodeId = "api-gateway",
                    label = "api-gateway",
                    nodeType = "AsynchronousEventBridge",
                    physicalAnchorId = "anchor_stage_rig_left_04",
                    semanticConstraints = listOf("Must process incoming DMX tokens below 11ms latency"),
                    loopEngineeringSkillTemplate = "autonomous-service-generator-v1",
                    isActive = true,
                ),
                NodeCardState(
                    nodeId = "auth-service",
                    label = "auth-service",
                    nodeType = "KafkaConsumer",
                    physicalAnchorId = "anchor_auth_01",
                    semanticConstraints = emptyList(),
                    loopEngineeringSkillTemplate = "auth-agent-v2",
                ),
            ),
            status = PanelStatus.STREAMING,
            logs = listOf(
                "Analyzing spatial context...",
                "Mapping intent to MCP schema...",
                "Broadcasting spec to workstation...",
            ),
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
