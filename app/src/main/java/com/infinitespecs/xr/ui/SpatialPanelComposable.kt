package com.infinitespecs.xr.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.infinitespecs.xr.bridge.McpSpecificationBridge

// ── Colour Palette (Amber Waveguide HUD Theme) ──────────────────────────────

private object HudColors {
    val Background = Color(0xDC0A0D14)    // 85% opacity near-black for readability
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
 * Waveguide-style Terminal HUD panel displaying agent connection, session selection,
 * active logs and confirmation cards.
 */
@Composable
fun InfiniteSpecsTerminalHudPanel(
    modifier: Modifier = Modifier,
    agentState: String = "OFFLINE",
    prompt: String = "",
    detail: String = "",
    options: List<String> = emptyList(),
    logs: List<String> = emptyList(),
    connectionState: String = "DISCONNECTED",
    sessions: List<McpSpecificationBridge.SessionInfo> = emptyList(),
    activeSessionId: String? = null,
    isListening: Boolean = false,
    viewMode: String = "SPACE",
    onViewModeToggle: () -> Unit = {},
    onStartDictation: () -> Unit = {},
    onStopDictation: () -> Unit = {},
    onConnect: (String, String) -> Unit = { _, _ -> },
    onSelectSession: (String) -> Unit = {},
    onDisconnect: () -> Unit = {},
    onInterrupt: () -> Unit = {},
    onSubmitPrompt: (String) -> Unit = {},
    onOptionSelected: (String) -> Unit = {},
    onTrigger: () -> Unit = {},
) {
    var showSessionSelector by remember { mutableStateOf(false) }
    
    // Automatically transition views based on connection state and session info
    LaunchedEffect(sessions) {
        if (sessions.isNotEmpty()) {
            showSessionSelector = true
        }
    }

    LaunchedEffect(connectionState) {
        if (connectionState == "DISCONNECTED") {
            showSessionSelector = false
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(HudColors.Background)
            .border(1.dp, HudColors.Border, RoundedCornerShape(12.dp))
            .padding(16.dp)
    ) {
        when {
            // 1. Active Terminal View
            connectionState == "CONNECTED" && activeSessionId != null -> {
                 ActiveTerminalView(
                    agentState = agentState,
                    prompt = prompt,
                    detail = detail,
                    options = options,
                    logs = logs,
                    activeSessionId = activeSessionId,
                    isListening = isListening,
                    viewMode = viewMode,
                    onViewModeToggle = onViewModeToggle,
                    onStartDictation = onStartDictation,
                    onStopDictation = onStopDictation,
                    onDisconnect = onDisconnect,
                    onInterrupt = onInterrupt,
                    onSubmitPrompt = onSubmitPrompt,
                    onOptionSelected = onOptionSelected
                )
            }
            
            // 2. Session Selector View
            showSessionSelector && sessions.isNotEmpty() -> {
                SessionSelectorView(
                    sessions = sessions,
                    connectionState = connectionState,
                    onSelectSession = onSelectSession,
                    onBack = {
                        onDisconnect()
                        showSessionSelector = false
                    }
                )
            }
            
            // 3. Pairing / Configuration Entry View
            else -> {
                PairingConfigView(
                    connectionState = connectionState,
                    onConnect = onConnect
                )
            }
        }
    }
}

// ── Sub-Views ──────────────────────────────────────────────────────────────

@Composable
private fun PairingConfigView(
    connectionState: String,
    onConnect: (String, String) -> Unit
) {
    var host by remember { mutableStateOf("10.0.2.2:3456") }
    var token by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(
                text = "TERMINAL PAIRING MODE",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = HudColors.TextPrimary
            )
            Text(
                text = "Configure connection parameters to your workstation daemon",
                fontFamily = FontFamily.Monospace,
                fontSize = 9.sp,
                color = HudColors.TextMuted
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
        ) {
            HudTextField(
                value = host,
                onValueChange = { host = it },
                label = "Workstation IP / Host"
            )
            HudTextField(
                value = token,
                onValueChange = { token = it },
                label = "Bearer Token"
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = when (connectionState) {
                    "CONNECTING" -> "[CONNECTING...]"
                    "ERROR" -> "[AUTH ERROR / DISCONNECTED]"
                    else -> "[AWAITING PAIRING]"
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = if (connectionState == "ERROR") Color.Red else HudColors.TextSecondary
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(HudColors.CardBackground)
                    .border(1.dp, HudColors.BorderActive, RoundedCornerShape(6.dp))
                    .clickable { onConnect(host, token) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "FETCH SESSIONS",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = HudColors.TextPrimary
                )
            }
        }
    }
}

@Composable
private fun SessionSelectorView(
    sessions: List<McpSpecificationBridge.SessionInfo>,
    connectionState: String,
    onSelectSession: (String) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "SELECT WORKSPACE SESSION",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = HudColors.TextPrimary
                )
                Text(
                    text = "Found ${sessions.size} active Claude sessions on workstation",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = HudColors.TextMuted
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .border(1.dp, HudColors.Border, RoundedCornerShape(4.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "BACK",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = HudColors.TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Session list container
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sessions) { session ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(HudColors.CardBackground)
                        .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onSelectSession(session.id) }
                        .padding(10.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = session.title.ifEmpty { "Untitled Session" },
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = HudColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "[${session.status ?: "idle"}]",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = HudColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Path: ${session.cwd}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 8.sp,
                            color = HudColors.TextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Status: ${if (connectionState == "CONNECTING") "Connecting to session..." else "Awaiting session selection"}",
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = HudColors.TextMuted
        )
    }
}

@Composable
private fun ActiveTerminalView(
    agentState: String,
    prompt: String,
    detail: String,
    options: List<String>,
    logs: List<String>,
    activeSessionId: String,
    isListening: Boolean,
    viewMode: String,
    onViewModeToggle: () -> Unit,
    onStartDictation: () -> Unit,
    onStopDictation: () -> Unit,
    onDisconnect: () -> Unit,
    onInterrupt: () -> Unit,
    onSubmitPrompt: (String) -> Unit,
    onOptionSelected: (String) -> Unit
) {
    var promptInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 1. Header Metadata
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TERMINAL ACTIVE // MONITORING",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = HudColors.TextPrimary
                )
                Text(
                    text = "SESSION: ${activeSessionId.take(12)}... // AGENT: $agentState",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    color = HudColors.TextMuted
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, HudColors.Border, RoundedCornerShape(4.dp))
                        .clickable { onViewModeToggle() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "MODE: $viewMode",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = HudColors.TextSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, HudColors.Border, RoundedCornerShape(4.dp))
                        .clickable { onDisconnect() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "LEAVE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = HudColors.TextSecondary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 2. Console Workspace Layout
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            if (agentState == "AWAITING_INPUT" && options.isNotEmpty()) {
                InteractiveInputCard(
                    prompt = prompt,
                    detail = detail,
                    options = options,
                    onOptionSelected = onOptionSelected
                )
            } else {
                TerminalLogsView(logs = logs, agentState = agentState)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 3. User prompt text input box
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                    .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                if (promptInput.isEmpty()) {
                    Text(
                        text = "Type message/instruction...",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = HudColors.TextMuted
                    )
                }
                BasicTextField(
                    value = promptInput,
                    onValueChange = { promptInput = it },
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = HudColors.TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(HudColors.TextPrimary),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(HudColors.CardBackground)
                    .border(1.dp, HudColors.BorderActive, RoundedCornerShape(6.dp))
                    .clickable {
                        if (promptInput.isNotEmpty()) {
                            onSubmitPrompt(promptInput)
                            promptInput = ""
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "SEND",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = HudColors.TextPrimary
                )
            }

            // Dictate Button
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isListening) Color.Red.copy(alpha = 0.2f) else HudColors.CardBackground)
                    .border(1.dp, if (isListening) Color.Red else HudColors.BorderActive, RoundedCornerShape(6.dp))
                    .clickable {
                        if (isListening) onStopDictation() else onStartDictation()
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (isListening) "LISTENING" else "DICTATE",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    color = if (isListening) Color.Red else HudColors.TextPrimary
                )
            }

            // Interrupt button for busy state
            if (agentState == "THINKING" || agentState == "EXECUTING") {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Red.copy(alpha = 0.2f))
                        .border(1.dp, Color.Red, RoundedCornerShape(6.dp))
                        .clickable { onInterrupt() }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "STOP",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        color = Color.Red
                    )
                }
            }
        }
    }
}

// ── Shared Sub-Components ───────────────────────────────────────────────────

@Composable
private fun HudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label.uppercase(),
            fontFamily = FontFamily.Monospace,
            fontSize = 9.sp,
            color = HudColors.TextSecondary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = HudColors.TextPrimary
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(HudColors.TextPrimary),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun TerminalLogsView(logs: List<String>, agentState: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
            .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        if (logs.isEmpty()) {
            Text(
                text = when (agentState) {
                    "OFFLINE" -> "SYSTEM OFFLINE\nWorkstation daemon disconnected."
                    "IDLE" -> "CONSOLE READY\nAwaiting instruction stream..."
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
                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                            color = HudColors.TextSecondary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveInputCard(
    prompt: String,
    detail: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(HudColors.CardBackground, RoundedCornerShape(8.dp))
            .border(1.dp, HudColors.BorderActive, RoundedCornerShape(8.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ACTION PERMISSION REQUEST",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = HudColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = prompt.ifEmpty { "Authorize agent actions:" },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = HudColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (detail.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .border(0.5.dp, HudColors.Border, RoundedCornerShape(4.dp))
                        .padding(6.dp)
                ) {
                    val lines = detail.split("\n")
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(lines) { line ->
                            val color = when {
                                line.startsWith("+") -> Color(0xFF4CAF50) // Green addition
                                line.startsWith("-") -> Color(0xFFF44336) // Red deletion
                                line.startsWith("@@") -> Color(0xFF00BCD4) // Cyan chunk header
                                else -> HudColors.TextSecondary
                            }
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = color
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Options Layout (Horizontal Stack for confirmations, otherwise Vertical)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            options.forEach { option ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .border(1.dp, HudColors.Border, RoundedCornerShape(6.dp))
                        .clickable { onOptionSelected(option) }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = HudColors.TextPrimary
                    )
                }
            }
        }
    }
}
