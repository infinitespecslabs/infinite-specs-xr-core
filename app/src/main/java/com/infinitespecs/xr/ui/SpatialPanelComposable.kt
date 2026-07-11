/*
 * SpatialPanelComposable.kt
 * infinite-specs-xr-core — Waveguide HUD UI Layer
 *
 * Evolved into "Terminal Mode" inspired by the Even Realities G2 smart glasses.
 * Renders an ultra-minimalist, semi-transparent monochrome amber HUD display
 * for hands-free tracking and interactive loop control.
 */

package com.infinitespecs.xr.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ── Colour Palette (Amber Waveguide HUD Theme) ──────────────────────────────

private object HudColors {
  val Background = Color(0x990A0D14)    // 60% opacity near-black
  val Border = Color(0x33FFB300)        // 20% opacity amber border
  val BorderActive = Color(0xFFFFB300)  // Solid glowing amber border
  
  val TextPrimary = Color(0xFFFFB300)   // Waveguide glowing amber
  val TextSecondary = Color(0xB3FFB300) // 70% opacity amber
  val TextMuted = Color(0x66FFB300)     // 40% opacity amber
  
  val CardBackground = Color(0x1AFFB300) // 10% opacity amber card background
  val CardBackgroundSelected = Color(0x40FFB300) // 25% opacity selected card
}

// ── Composable HUD UI Elements ──────────────────────────────────────────────

/**
 * Waveguide-style Terminal HUD panel displaying agent compilation logs
 * and offering direct input options when the loop requires human feedback.
 */
@Composable
fun InfiniteSpecsTerminalHudPanel(
  modifier: Modifier = Modifier,
  agentState: String = "OFFLINE",
  prompt: String = "",
  options: List<String> = emptyList(),
  logs: List<String> = emptyList(),
  onOptionSelected: (String) -> Unit = {},
  onTrigger: () -> Unit = {},
) {
  Box(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(HudColors.Background)
      .border(1.dp, HudColors.Border, RoundedCornerShape(12.dp))
      .padding(16.dp)
  ) {
    Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      // 1. Header Row: Connection and Status Metadata
      HudHeader(agentState = agentState)

      Spacer(modifier = Modifier.height(10.dp))

      // 2. Interactive Area: Prompt or Terminal Stream
      if (agentState == "AWAITING_INPUT" && options.isNotEmpty()) {
        InteractiveInputCard(
          prompt = prompt,
          options = options,
          onOptionSelected = onOptionSelected
        )
      } else {
        TerminalLogsView(logs = logs, agentState = agentState)
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 3. Footer Control Bar
      HudFooter(agentState = agentState, onTrigger = onTrigger)
    }
  }
}

// ── Sub-Composables ──────────────────────────────────────────────────────────

@Composable
private fun HudHeader(agentState: String) {
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  
  // Flashing animation for awaiting input
  val flashAlpha by infiniteTransition.animateFloat(
    initialValue = 0.4f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(
      animation = tween(800, easing = LinearEasing),
      repeatMode = RepeatMode.Reverse
    ),
    label = "flash"
  )

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Column {
      Text(
        text = "TERMINAL MODE // ACTIVE",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        color = HudColors.TextPrimary
      )
      Text(
        text = "SESSION: claude-code-75e // WORKSTATION: localhost",
        fontFamily = FontFamily.Monospace,
        fontSize = 9.sp,
        color = HudColors.TextMuted
      )
    }
    
    // Status text wrapper
    val statusAlpha = if (agentState == "AWAITING_INPUT") flashAlpha else 1f
    Box(
      modifier = Modifier
        .alpha(statusAlpha)
        .border(1.dp, HudColors.TextPrimary, RoundedCornerShape(4.dp))
        .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
      Text(
        text = "[$agentState]",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        color = HudColors.TextPrimary
      )
    }
  }
}

@Composable
private fun ColumnScope.TerminalLogsView(logs: List<String>, agentState: String) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .weight(1f)
      .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
      .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
      .padding(10.dp)
  ) {
    if (logs.isEmpty()) {
      Text(
        text = when (agentState) {
          "OFFLINE" -> "SYSTEM OFFLINE\nWorkstation daemon disconnected.\n\nOpen http://localhost:3000\nor run 'npm start' on your Macbook."
          "IDLE" -> "CONSOLE READY\nAwaiting spatial telemetry transcript..."
          else -> "STREAMING OUTPUT..."
        },
        fontFamily = FontFamily.Monospace,
        fontSize = 11.sp,
        color = HudColors.TextSecondary,
        modifier = Modifier.align(Alignment.Center),
        textAlign = TextAlign.Center
      )
    } else {
      LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        reverseLayout = false
      ) {
        items(logs) { log ->
          Row(modifier = Modifier.fillMaxWidth()) {
            Text(
              text = "> ",
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              color = HudColors.TextMuted
            )
            Text(
              text = log,
              fontFamily = FontFamily.Monospace,
              fontSize = 10.sp,
              color = HudColors.TextSecondary,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}

@Composable
private fun ColumnScope.InteractiveInputCard(
  prompt: String,
  options: List<String>,
  onOptionSelected: (String) -> Unit
) {
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .weight(1f)
      .background(HudColors.CardBackground, RoundedCornerShape(8.dp))
      .border(1.dp, HudColors.BorderActive, RoundedCornerShape(8.dp))
      .padding(12.dp),
    verticalArrangement = Arrangement.SpaceBetween
  ) {
    Column {
      Text(
        text = "INPUT REQUESTED",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = HudColors.TextPrimary
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = prompt.ifEmpty { "Choose configuration option:" },
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = HudColors.TextSecondary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Options Layout (Vertical Stack for clear targeting)
    Column(
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      options.forEach { option ->
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
            .clickable { onOptionSelected(option) }
            .padding(10.dp)
        ) {
          Text(
            text = "[ ] $option",
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = HudColors.TextPrimary,
            modifier = Modifier.align(Alignment.CenterStart)
          )
        }
      }
    }
  }
}

@Composable
private fun HudFooter(agentState: String, onTrigger: () -> Unit) {
  if (agentState == "IDLE") {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .background(HudColors.CardBackground)
        .border(1.5.dp, HudColors.BorderActive, RoundedCornerShape(6.dp))
        .clickable { onTrigger() }
        .padding(vertical = 10.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = "ENGAGE PERCEPTION PIPELINE",
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        color = HudColors.TextPrimary
      )
    }
  } else {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(6.dp))
        .background(Color.Transparent)
        .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
        .padding(vertical = 10.dp),
      contentAlignment = Alignment.Center
    ) {
      Text(
        text = when (agentState) {
          "OFFLINE" -> "DAEMON OFFLINE // CHECK CONSOLE"
          "THINKING" -> "AGENT IS THINKING..."
          "EXECUTING" -> "COMPILING WORKSPACE CODE..."
          "AWAITING_INPUT" -> "AWAITING REMOTE SELECTION..."
          "SUCCESS" -> "VERIFICATION COMPLETED // READY"
          else -> "PROCESSING DEPLOYMENT STREAM..."
        },
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        color = HudColors.TextSecondary
      )
    }
  }
}

// ── Compose Previews ─────────────────────────────────────────────────────────

@Preview(name = "HUD Waveguide — Idle", showBackground = true, backgroundColor = 0xFF050505)
@Composable
private fun PreviewTerminalHudIdle() {
  InfiniteSpecsTerminalHudPanel(
    agentState = "IDLE",
    logs = emptyList()
  )
}

@Preview(name = "HUD Waveguide — Awaiting Input", showBackground = true, backgroundColor = 0xFF050505)
@Composable
private fun PreviewTerminalHudAwaitingInput() {
  InfiniteSpecsTerminalHudPanel(
    agentState = "AWAITING_INPUT",
    prompt = "Select DMX channel footprint:",
    options = listOf("1-Ch (Dimmer)", "3-Ch (RGB)", "4-Ch (RGBA)"),
    logs = listOf("Ingested specification", "Created workspace worktree")
  )
}

@Preview(name = "HUD Waveguide — Streaming logs", showBackground = true, backgroundColor = 0xFF050505)
@Composable
private fun PreviewTerminalHudStreaming() {
  InfiniteSpecsTerminalHudPanel(
    agentState = "EXECUTING",
    logs = listOf(
      "Analyzing spatial anchors...",
      "Generating code KafkaConsumer.kt",
      "Running compiler task :app:compileKotlin",
      "[compiler] Symbols resolved successfully."
    )
  )
}
