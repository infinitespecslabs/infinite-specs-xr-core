#!/usr/bin/env node

/**
 * Custom Clean-room Workstation Host Daemon.
 * Bypasses all proprietary dependencies on @evenrealities/even-terminal.
 * Serves as the developer workstation gateway, handling local command execution,
 * file editing, and streaming state/events to the Android XR Headset HUD.
 */

const http = require('http');
const url = require('url');
const { exec } = require('child_process');
const fs = require('fs');
const path = require('path');

const PORT = 3456;

// Local active session state
let activeSession = {
  id: "clean-session",
  title: "Clean-room Spatial Workspace",
  status: "idle",
  cwd: process.cwd()
};

let activeClients = [];
let pendingPermission = null;

const server = http.createServer((req, res) => {
  // CORS Headers
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization');

  if (req.method === 'OPTIONS') {
    res.writeHead(200);
    res.end();
    return;
  }

  const parsedUrl = url.parse(req.url, true);
  const pathname = parsedUrl.pathname;

  // 1. GET /api/sessions -> List available workspace sessions
  if (pathname === '/api/sessions' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify([activeSession]));
    return;
  }

  // 2. GET /api/events -> Server-Sent Events stream
  if (pathname === '/api/events' && req.method === 'GET') {
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    });

    console.log(`[CustomDaemon] XR headset connected to event stream.`);
    res.write('data: :ok\n\n');
    
    activeClients.push(res);
    sendSse(res, { state: activeSession.status, sessionId: activeSession.id });

    req.on('close', () => {
      console.log(`[CustomDaemon] XR headset disconnected.`);
      activeClients = activeClients.filter(c => c !== res);
    });
    return;
  }

  // 3. POST /api/prompt -> Submit command/instruction from headset
  if (pathname === '/api/prompt' && req.method === 'POST') {
    collectJson(req, (data) => {
      const prompt = data.prompt;
      console.log(`[CustomDaemon] Headset prompt: ${prompt}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true }));

      handleUserPrompt(prompt);
    });
    return;
  }

  // 4. POST /api/permission-response -> Authorize execution
  if (pathname === '/api/permission-response' && req.method === 'POST') {
    collectJson(req, (data) => {
      console.log(`[CustomDaemon] Permission response: ${data.option}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true }));

      if (pendingPermission) {
        const resolve = pendingPermission.resolve;
        pendingPermission = null;
        resolve(data.option === 'Proceed');
      }
    });
    return;
  }

  // Fallback 404
  res.writeHead(404);
  res.end();
});

// Helper: send SSE payload
function sendSse(clientRes, dataObj) {
  clientRes.write(`data: ${JSON.stringify(dataObj)}\n\n`);
}

// Helper: broadcast log update to all connected clients
function broadcastLog(text) {
  console.log(`[LOG] ${text}`);
  activeClients.forEach(c => sendSse(c, { text }));
}

// Helper: broadcast state change to all connected clients
function broadcastState(state) {
  activeSession.status = state;
  console.log(`[STATE] ${state}`);
  activeClients.forEach(c => sendSse(c, { state, sessionId: activeSession.id }));
}

// Helper: collect payload from POST requests
function collectJson(req, callback) {
  let body = '';
  req.on('data', chunk => { body += chunk.toString(); });
  req.on('end', () => {
    try {
      callback(JSON.parse(body));
    } catch (e) {
      callback({});
    }
  });
}

/**
 * Handle incoming developer prompt from the XR headset.
 * Implements a simple command execution router or AI model execution.
 */
async function handleUserPrompt(prompt) {
  broadcastState('thinking');
  broadcastLog(`Received spatial instruction: "${prompt}"`);

  // Detect simple shell command triggers
  if (prompt.toLowerCase().startsWith("run ")) {
    const command = prompt.substring(4);
    
    // Request permission from headset before running arbitrary command
    const authorized = await requestPermission(
      `Execute shell command: "${command}"?`,
      `CommandLine: ${command}\nCwd: ${process.cwd()}`
    );

    if (authorized) {
      broadcastState('executing');
      broadcastLog(`Executing: ${command}`);
      
      exec(command, (error, stdout, stderr) => {
        if (error) {
          broadcastLog(`Error: ${error.message}`);
        }
        if (stdout) {
          stdout.split('\n').forEach(line => {
            if (line.trim()) broadcastLog(line);
          });
        }
        if (stderr) {
          stderr.split('\n').forEach(line => {
            if (line.trim()) broadcastLog(`[stderr] ${line}`);
          });
        }
        broadcastState('idle');
      });
    } else {
      broadcastLog("Execution denied by user.");
      broadcastState('idle');
    }
  } else {
    // Simulate generic AI text response
    setTimeout(() => {
      broadcastLog("Clean-room daemon processed natural language query.");
      broadcastLog("For arbitrary task execution, try starting prompt with 'run <command>'.");
      broadcastState('idle');
    }, 2000);
  }
}

/**
 * Sends a permission request payload to the headset and awaits response asynchronously.
 */
function requestPermission(description, detail) {
  return new Promise((resolve) => {
    pendingPermission = { resolve };

    broadcastState('awaiting_input');
    activeClients.forEach(c => sendSse(c, {
      toolName: "custom_exec",
      toolUseId: `task-${Date.now()}`,
      description,
      detail,
      options: [
        { text: "Proceed", primary: true },
        { text: "Cancel", primary: false }
      ]
    }));
  });
}

server.listen(PORT, () => {
  console.log(`Clean-room Workstation Host Daemon listening on http://localhost:${PORT}`);
});
