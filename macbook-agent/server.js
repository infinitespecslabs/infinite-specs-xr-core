import express from 'express';
import cors from 'cors';
import http from 'http';
import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

const app = express();
const PORT = 3000;

app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname, 'public')));

// Global state
let deviceIp = 'localhost';
let connectionStatus = 'disconnected'; // disconnected, connecting, connected
let currentAgentState = 'IDLE';        // IDLE, THINKING, EXECUTING, AWAITING_INPUT, SUCCESS
let activeIntent = null;
let terminalLogs = [];
let sseClients = [];
let androidSseRequest = null;
let pendingInputPromise = null;

// Helper to push logs and broadcast to dashboard
function addLog(text, type = 'info') {
  const timestamp = new Date().toLocaleTimeString();
  const logEntry = { timestamp, text, type };
  terminalLogs.push(logEntry);
  if (terminalLogs.length > 100) {
    terminalLogs.shift();
  }
  broadcast({ type: 'log', data: logEntry });
  console.log(`[${type.toUpperCase()}] ${text}`);
}

function broadcast(payload) {
  const data = JSON.stringify(payload);
  sseClients.forEach(client => {
    client.write(`data: ${data}\n\n`);
  });
}

// POST logs back to the Android XR app Ktor server
function postLogToAndroidXR(text) {
  const ip = (deviceIp === 'localhost' || deviceIp.trim() === '') ? '127.0.0.1' : deviceIp;
  sendPost(ip, '/mcp/logs', text);
}

// POST active state payloads back to the Android XR Ktor server
function postStateToAndroidXR(state, prompt = '', options = [], logText = '') {
  const ip = (deviceIp === 'localhost' || deviceIp.trim() === '') ? '127.0.0.1' : deviceIp;
  const payload = JSON.stringify({ state, prompt, options, log: logText });
  sendPost(ip, '/mcp/agent-state', payload, 'application/json');
}

function sendPost(ip, pathUrl, data, contentType = 'text/plain') {
  const options = {
    hostname: ip,
    port: 8080,
    path: pathUrl,
    method: 'POST',
    headers: {
      'Content-Type': contentType,
      'Content-Length': Buffer.byteLength(data)
    }
  };

  const req = http.request(options, (res) => {
    // Successfully sent
  });

  req.on('error', (e) => {
    // Suppress console spam if offline, but show error in terminal logs occasionally
    if (pathUrl === '/mcp/agent-state') {
      console.log(`[DAEMON-OFFLINE] Unable to sync state to Android XR`);
    }
  });

  req.write(data);
  req.end();
}

// Connect to Android XR App SSE stream
function connectToAndroidXR() {
  if (androidSseRequest) {
    androidSseRequest.destroy();
  }

  const ip = (deviceIp === 'localhost' || deviceIp.trim() === '') ? '127.0.0.1' : deviceIp;
  connectionStatus = 'connecting';
  broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
  addLog(`Connecting to Android XR spec daemon at http://${ip}:8080/mcp/sse...`, 'system');

  const options = {
    hostname: ip,
    port: 8080,
    path: '/mcp/sse',
    method: 'GET',
    headers: {
      'Accept': 'text/event-stream'
    }
  };

  const req = http.get(options, (res) => {
    if (res.statusCode !== 200) {
      connectionStatus = 'disconnected';
      broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
      addLog(`Failed to connect. Server returned status code ${res.statusCode}`, 'error');
      return;
    }

    connectionStatus = 'connected';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    addLog(`Connected to Android XR specification stream!`, 'success');
    postStateToAndroidXR('IDLE', '', [], '[Macbook Agent] Pair success. Waveguide sync ready.');

    let buffer = '';

    res.on('data', (chunk) => {
      buffer += chunk.toString();
      const parts = buffer.split('\n\n');
      buffer = parts.pop(); // save trailing partial data in buffer

      for (const part of parts) {
        if (!part.trim()) continue;

        let eventName = 'message';
        let dataStr = '';

        const lines = part.split('\n');
        for (const line of lines) {
          if (line.startsWith('event:')) {
            eventName = line.substring(6).trim();
          } else if (line.startsWith('data:')) {
            dataStr = line.substring(5).trim();
          }
        }

        if (eventName === 'specification' && dataStr) {
          try {
            const spec = JSON.parse(dataStr);
            handleIncomingSpecification(spec);
          } catch (e) {
            addLog(`Error parsing specification payload: ${e.message}`, 'error');
          }
        } else if (eventName === 'connected') {
          addLog(`XR Spec Daemon says: ${dataStr}`, 'system');
        }
      }
    });

    res.on('end', () => {
      connectionStatus = 'disconnected';
      broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
      addLog(`Android XR connection closed by remote host.`, 'warning');
    });
  });

  req.on('error', (e) => {
    connectionStatus = 'disconnected';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    addLog(`Connection error to Android XR spec daemon: ${e.message}`, 'error');
  });

  androidSseRequest = req;
}

// Process incoming specification & trigger Agent Loop
function handleIncomingSpecification(spec) {
  activeIntent = spec;
  broadcast({ type: 'specification', data: spec });
  addLog(`Received architectural intent specification for: ${spec.intent_node}`, 'system');

  // Trigger autonomous compilation and testing loop
  runAgentLoop(spec);
}

// Autonomous Agent Loop Simulation
async function runAgentLoop(spec) {
  const nodeType = spec.intent_node || 'UnknownNode';
  const anchorId = spec.spatial_context_id || 'floating_context';
  const targetSkill = spec.loop_constraints?.target_skill || 'autonomous-service-generator-v1';
  const constraints = spec.loop_constraints?.rules || [];

  const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

  try {
    // Stage 1: Ingesting
    currentAgentState = 'THINKING';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    postStateToAndroidXR('THINKING', '', [], `[Agent] Ingesting specification: ${nodeType}`);
    addLog(`[Loop Stage 1/5] Ingesting intent specifications...`, 'agent');
    await sleep(800);
    addLog(`Targeting spatial node: '${nodeType}' anchored to '${anchorId}'`, 'info');
    addLog(`Assigned Skill Template: '${targetSkill}'`, 'info');
    constraints.forEach((rule, idx) => {
      addLog(`Constraint #${idx + 1}: "${rule}"`, 'info');
    });

    // Stage 2: Codebase Sync
    currentAgentState = 'EXECUTING';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    postStateToAndroidXR('EXECUTING', '', [], '[Agent] Mapping worktree structures...');
    await sleep(1000);
    addLog(`[Loop Stage 2/5] Creating simulated agent worktree...`, 'agent');
    
    // Ensure simulated-agent-worktree directory exists
    const worktreeDir = path.join(__dirname, '..', 'simulated-agent-worktree');
    if (!fs.existsSync(worktreeDir)) {
      fs.mkdirSync(worktreeDir, { recursive: true });
    }

    // Stage 3: Remote Interactive Input
    addLog(`[Loop Stage 3/5] Awaiting waveguide HUD input choices...`, 'agent');
    currentAgentState = 'AWAITING_INPUT';
    const promptMessage = `Choose configuration footprint for ${nodeType}:`;
    const inputOptions = ["1-Ch (Dimmer)", "3-Ch (RGB Direct)", "4-Ch (RGBA Dynamic)"];
    
    // Broadcast state to dashboard and glasses
    broadcast({ 
      type: 'status', 
      data: { connectionStatus, deviceIp, currentAgentState, prompt: promptMessage, options: inputOptions } 
    });
    postStateToAndroidXR('AWAITING_INPUT', promptMessage, inputOptions, '[Agent] Awaiting waveguide HUD response...');

    // Pause thread execution until promise is resolved by API call
    const selectedOption = await new Promise((resolve) => {
      pendingInputPromise = resolve;
    });

    // Resumed execution
    currentAgentState = 'EXECUTING';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    addLog(`Received user option input: "${selectedOption}"`, 'success');
    postStateToAndroidXR('EXECUTING', '', [], `[Agent] Option locked: ${selectedOption}`);
    
    // Write out code using selectedOption parameter
    const filePath = path.join(worktreeDir, `${nodeType}.kt`);
    const constraintsComments = constraints.map(c => ` * - ${c}`).join('\n');
    
    const kotlinCode = `package com.infinitespecs.xr.generated

import java.util.UUID

/**
 * AUTO-GENERATED BY SPATIAL AGENT LOOP
 * --------------------------------------------------
 * Physical Anchor Link: ${anchorId}
 * Loop Skill Template: ${targetSkill}
 * Applied Configuration: ${selectedOption}
 * Generated: ${new Date().toISOString()}
 * 
 * Verified Architectural Constraints:
${constraintsComments}
 */
class ${nodeType} {
    private val componentId = UUID.randomUUID()
    private val channelFootprint = "${selectedOption}"
    
    init {
        println("${nodeType} spatial node activated with configuration: $channelFootprint")
    }
    
    fun processSpatialIntent() {
        println("Processing telemetry for anchor ${anchorId} with footprint: $channelFootprint")
    }
}
`;

    fs.writeFileSync(filePath, kotlinCode, 'utf8');
    addLog(`Generated Kotlin class file: simulated-agent-worktree/${nodeType}.kt`, 'success');
    postStateToAndroidXR('EXECUTING', '', [], `[Agent] Written: ${nodeType}.kt`);

    // Stage 4: Verification & Local Compile
    await sleep(1200);
    addLog(`[Loop Stage 4/5] Starting compilation & verification check...`, 'agent');
    addLog(`> Running build syntax analysis...`, 'info');
    await sleep(1000);
    
    addLog(`[compiler] Parsing 1 Kotlin file...`, 'info');
    addLog(`[compiler] Checking symbol linkages for footprint config "${selectedOption}"...`, 'info');
    await sleep(800);
    addLog(`[compiler] Code syntactically valid. Linkages complete.`, 'success');
    postStateToAndroidXR('EXECUTING', '', [], '[Agent] Class verification complete.');

    // Stage 5: Run Tests & Closed Loop
    await sleep(1200);
    addLog(`[Loop Stage 5/5] Executing constraint validation tests...`, 'agent');
    addLog(`> Running check: verifySpatialTelemetryFoldsIntoValidMcpSpecification...`, 'info');
    await sleep(1000);
    addLog(`[TEST RUNNER] SUCCESS: verifySpatialTelemetryFoldsIntoValidMcpSpecification passed (34ms)`, 'success');
    
    currentAgentState = 'SUCCESS';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    postStateToAndroidXR('SUCCESS', '', [], '[Agent] Strange loop closed successfully.');
    addLog(`Closed loop complete. Code successfully updated, compiled, and verified.`, 'success');

    // Return to IDLE after a short pause
    await sleep(3000);
    currentAgentState = 'IDLE';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    postStateToAndroidXR('IDLE', '', [], '');
    addLog(`Agent loop returned to IDLE.`, 'system');

  } catch (error) {
    currentAgentState = 'IDLE';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp, currentAgentState } });
    addLog(`Error inside Agent Loop execution: ${error.message}`, 'error');
    postStateToAndroidXR('IDLE', '', [], `[Agent] FAILED: ${error.message}`);
  }
}

// ── HTTP API REST Endpoints ────────────────────────────────────────────────

// Serve dashboard state
app.get('/api/state', (req, res) => {
  res.json({
    deviceIp,
    connectionStatus,
    currentAgentState,
    activeIntent,
    terminalLogs
  });
});

// Update targeted Android XR device IP and reconnect
app.post('/api/config', (req, res) => {
  const { ip } = req.body;
  if (!ip) {
    return res.status(400).json({ error: 'IP address is required' });
  }
  deviceIp = ip;
  addLog(`Configured targeting IP address: ${deviceIp}`, 'system');
  connectToAndroidXR();
  res.json({ success: true, deviceIp, connectionStatus, currentAgentState });
});

// Ingest interactive input from the XR HUD or local web override
app.post('/api/agent-input', (req, res) => {
  const { selectedOption } = req.body;
  if (!selectedOption) {
    return res.status(400).json({ error: 'selectedOption is required' });
  }

  if (pendingInputPromise) {
    pendingInputPromise(selectedOption);
    pendingInputPromise = null;
    addLog(`Inbound interactive selection received: "${selectedOption}"`, 'system');
    res.json({ success: true });
  } else {
    res.status(400).json({ error: 'No agent loop is currently awaiting input' });
  }
});

// Trigger a mock specification to run agent loop offline
app.post('/api/mock-trigger', (req, res) => {
  const mockSpec = {
    mcp_protocol_version: '2026-06-01',
    intent_node: req.body.nodeType || 'DMXLightingController',
    spatial_context_id: req.body.anchorId || 'anchor_stage_rig_left_04',
    loop_constraints: {
      rules: req.body.constraints || [
        'Must process incoming DMX tokens below 11ms latency',
        'Should auto-recover when connection drops'
      ],
      target_skill: req.body.skill || 'autonomous-service-generator-v1'
    },
    real_world_telemetry: {
      originX: 0,
      originY: 0,
      originZ: 0,
      directionX: 0,
      directionY: 0,
      directionZ: -1
    }
  };

  handleIncomingSpecification(mockSpec);
  res.json({ success: true, spec: mockSpec });
});

// SSE connection for the web dashboard UI
app.get('/api/events', (req, res) => {
  res.writeHead(200, {
    'Content-Type': 'text/event-stream',
    'Cache-Control': 'no-cache',
    'Connection': 'keep-alive'
  });

  res.write(`data: ${JSON.stringify({ 
    type: 'init', 
    data: { deviceIp, connectionStatus, currentAgentState, activeIntent, terminalLogs } 
  })}\n\n`);

  sseClients.push(res);

  req.on('close', () => {
    sseClients = sseClients.filter(c => c !== res);
  });
});

// Start express server and connect to XR device
app.listen(PORT, () => {
  console.log(`\n🌀 Macbook Agent Loop Web Server running at http://localhost:${PORT}`);
  addLog(`Macbook Agent Loop Web Server started. Listening on port ${PORT}`, 'system');
  
  // Attempt initial connection to localhost (ADB forwarded)
  connectToAndroidXR();
});
