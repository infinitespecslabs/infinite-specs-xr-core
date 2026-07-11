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
let activeIntent = null;
let terminalLogs = [];
let sseClients = [];
let androidSseRequest = null;

// Ensure logs function pushes to global terminalLogs list and syncs to frontend clients
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
  if (deviceIp === 'localhost' || deviceIp.trim() === '') {
    // If localhost, use 127.0.0.1
    sendPost('127.0.0.1', text);
  } else {
    sendPost(deviceIp, text);
  }
}

function sendPost(ip, text) {
  const options = {
    hostname: ip,
    port: 8080,
    path: '/mcp/logs',
    method: 'POST',
    headers: {
      'Content-Type': 'text/plain',
      'Content-Length': Buffer.byteLength(text)
    }
  };

  const req = http.request(options, (res) => {
    // Successfully sent
  });

  req.on('error', (e) => {
    addLog(`Unable to push log to Android XR: ${e.message}`, 'error');
  });

  req.write(text);
  req.end();
}

// Connect to Android XR App SSE stream
function connectToAndroidXR() {
  if (androidSseRequest) {
    androidSseRequest.destroy();
  }

  const ip = (deviceIp === 'localhost' || deviceIp.trim() === '') ? '127.0.0.1' : deviceIp;
  connectionStatus = 'connecting';
  broadcast({ type: 'status', data: { connectionStatus, deviceIp } });
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
      broadcast({ type: 'status', data: { connectionStatus, deviceIp } });
      addLog(`Failed to connect. Server returned status code ${res.statusCode}`, 'error');
      return;
    }

    connectionStatus = 'connected';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp } });
    addLog(`Connected to Android XR specification stream!`, 'success');
    postLogToAndroidXR("[Macbook Agent] Bidirectional loop handshake success.");

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
      broadcast({ type: 'status', data: { connectionStatus, deviceIp } });
      addLog(`Android XR connection closed by remote host.`, 'warning');
    });
  });

  req.on('error', (e) => {
    connectionStatus = 'disconnected';
    broadcast({ type: 'status', data: { connectionStatus, deviceIp } });
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
    addLog(`[Loop Stage 1/5] Ingesting intent specifications...`, 'agent');
    await sleep(800);
    addLog(`Targeting spatial node: '${nodeType}' anchored to '${anchorId}'`, 'info');
    addLog(`Assigned Skill Template: '${targetSkill}'`, 'info');
    constraints.forEach((rule, idx) => {
      addLog(`Constraint #${idx + 1}: "${rule}"`, 'info');
    });
    postLogToAndroidXR(`[Agent] Ingested spec: ${nodeType}`);

    // Stage 2: Codebase Sync & Generation
    await sleep(1500);
    addLog(`[Loop Stage 2/5] Creating simulated agent worktree...`, 'agent');
    
    // Ensure simulated-agent-worktree directory exists
    const worktreeDir = path.join(__dirname, '..', 'simulated-agent-worktree');
    if (!fs.existsSync(worktreeDir)) {
      fs.mkdirSync(worktreeDir, { recursive: true });
    }

    const filePath = path.join(worktreeDir, `${nodeType}.kt`);
    const constraintsComments = constraints.map(c => ` * - ${c}`).join('\n');
    
    const kotlinCode = `package com.infinitespecs.xr.generated

import java.util.UUID

/**
 * AUTO-GENERATED BY SPATIAL AGENT LOOP
 * --------------------------------------------------
 * Physical Anchor Link: ${anchorId}
 * Loop Skill Template: ${targetSkill}
 * Generated: ${new Date().toISOString()}
 * 
 * Verified Architectural Constraints:
${constraintsComments}
 */
class ${nodeType} {
    private val componentId = UUID.randomUUID()
    
    init {
        println("${nodeType} spatial node activated.")
        // Applied: ${targetSkill} constraints
    }
    
    fun processSpatialIntent() {
        // Simulating logic matching spatial telemetry inputs
        println("Processing telemetry for anchor ${anchorId}")
    }
}
`;

    fs.writeFileSync(filePath, kotlinCode, 'utf8');
    addLog(`Generated Kotlin class file: simulated-agent-worktree/${nodeType}.kt`, 'success');
    postLogToAndroidXR(`[Agent] Generated ${nodeType}.kt file`);

    // Stage 3: Verification & Local Compile
    await sleep(1500);
    addLog(`[Loop Stage 3/5] Starting compilation & verification check...`, 'agent');
    addLog(`> Running build syntax analysis...`, 'info');
    await sleep(1000);
    
    // Output simulated compiler warnings / lines
    addLog(`[compiler] Parsing 1 Kotlin file...`, 'info');
    addLog(`[compiler] Checking symbol linkages for spatial contexts...`, 'info');
    await sleep(800);
    addLog(`[compiler] Code syntactically valid. Linkages complete.`, 'success');
    postLogToAndroidXR(`[Agent] Compiled successfully`);

    // Stage 4: Run Tests
    await sleep(1200);
    addLog(`[Loop Stage 4/5] Executing constraint validation tests...`, 'agent');
    addLog(`> Running check: verifySpatialTelemetryFoldsIntoValidMcpSpecification...`, 'info');
    await sleep(1000);
    addLog(`[TEST RUNNER] Found 1 test scenario matching loop constraints.`, 'info');
    addLog(`[TEST RUNNER] SUCCESS: verifySpatialTelemetryFoldsIntoValidMcpSpecification passed (34ms)`, 'success');
    postLogToAndroidXR(`[Agent] Verification checks PASSED`);

    // Stage 5: Closing Loop
    await sleep(1200);
    addLog(`[Loop Stage 5/5] Synthesizing state feedback...`, 'agent');
    addLog(`Closed loop complete. Code successfully updated, compiled, and verified.`, 'success');
    postLogToAndroidXR(`[Agent] Strange Loop Closed - Active`);

  } catch (error) {
    addLog(`Error inside Agent Loop execution: ${error.message}`, 'error');
    postLogToAndroidXR(`[Agent] FAILED: ${error.message}`);
  }
}

// ── HTTP API REST Endpoints ────────────────────────────────────────────────

// Serve dashboard state
app.get('/api/state', (req, res) => {
  res.json({
    deviceIp,
    connectionStatus,
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
  res.json({ success: true, deviceIp, connectionStatus });
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

  res.write(`data: ${JSON.stringify({ type: 'init', data: { deviceIp, connectionStatus, activeIntent, terminalLogs } })}\n\n`);

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
