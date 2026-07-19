#!/usr/bin/env node

/**
 * Mock even-terminal Workstation Daemon Server.
 * Simulates SSE event streams and REST endpoints for Android XR client verification.
 * Runs on Node.js using only native standard libraries.
 */

const http = require('http');
const url = require('url');

const PORT = 3456;

// Mock session databases
let sessions = [
  { id: "session-1", title: "Database Refactor Phase 2", status: "idle", cwd: "/Users/alex/infinite-specs-db" },
  { id: "session-2", title: "Configure Ktor SSE Engine", status: "idle", cwd: "/Users/alex/infinite-specs-xr-core" }
];

// Active SSE client connections
let activeClients = [];

// Simulation task timeout
let currentTaskTimeout = null;

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
    res.end(JSON.stringify(sessions));
    return;
  }

  // 2. GET /api/events -> Server-Sent Events stream
  if (pathname === '/api/events' && req.method === 'GET') {
    const sessionId = parsedUrl.query.sessionId || 'session-1';
    
    // Setup SSE connection
    res.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      'Connection': 'keep-alive'
    });

    console.log(`[SSE] Client connected to session: ${sessionId}`);
    
    // Heartbeat & Connection OK
    res.write('data: :ok\n\n');
    
    // Store connection
    const client = { res, sessionId };
    activeClients.push(client);

    // Initial Status Event
    sendSse(res, { state: 'idle', sessionId });

    req.on('close', () => {
      console.log(`[SSE] Client disconnected`);
      activeClients = activeClients.filter(c => c.res !== res);
    });
    return;
  }

  // 3. POST /api/prompt -> Submit text instruction or dictation
  if (pathname === '/api/prompt' && req.method === 'POST') {
    collectJson(req, (data) => {
      console.log(`[POST] Received prompt instruction: ${data.prompt}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true }));

      // Trigger mock agent thinking/executing loop
      triggerMockExecutionCycle(data.prompt);
    });
    return;
  }

  // 4. POST /api/permission-response -> Authorize/deny shell commands or file updates
  if (pathname === '/api/permission-response' && req.method === 'POST') {
    collectJson(req, (data) => {
      console.log(`[POST] User authorized action with option: ${data.option}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true }));

      if (data.option === 'Proceed') {
        broadcastLog("Executing authorized tool operations...");
        broadcastState('executing');
        
        setTimeout(() => {
          broadcastLog("Tool executed successfully. Codebase synchronized.");
          broadcastState('idle');
        }, 1500);
      } else {
        broadcastLog("Permission denied. Aborting current task.");
        broadcastState('idle');
      }
    });
    return;
  }

  // 5. POST /api/question-response -> Answer developer clarifications
  if (pathname === '/api/question-response' && req.method === 'POST') {
    collectJson(req, (data) => {
      console.log(`[POST] Answered clarification question: ${data.option || data.response}`);
      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: true }));

      broadcastLog(`Clarification received: "${data.option || data.response}". Continuing execution.`);
      broadcastState('executing');

      setTimeout(() => {
        broadcastLog("Tasks completed successfully.");
        broadcastState('idle');
      }, 1500);
    });
    return;
  }

  // 6. POST /api/interrupt -> Stop current agent thinking/executing cycle
  if (pathname === '/api/interrupt' && req.method === 'POST') {
    console.log(`[POST] Received agent interrupt request`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ success: true }));

    if (currentTaskTimeout) {
      clearTimeout(currentTaskTimeout);
      currentTaskTimeout = null;
    }
    broadcastLog("Execution interrupted by developer.");
    broadcastState('idle');
    return;
  }

  // Fallback 404
  res.writeHead(404);
  res.end();
});

// Helper: send data chunk formatted as SSE
function sendSse(clientRes, dataObj) {
  clientRes.write(`data: ${JSON.stringify(dataObj)}\n\n`);
}

// Helper: broadcast log update to all connected clients
function broadcastLog(text) {
  console.log(`[LOG] ${text}`);
  activeClients.forEach(c => sendSse(c.res, { text }));
}

// Helper: broadcast state change to all connected clients
function broadcastState(state) {
  console.log(`[STATE] Transitioned to: ${state}`);
  activeClients.forEach(c => sendSse(c.res, { state, sessionId: c.sessionId }));
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

// Simulates a full agent work lifecycle: thinking -> tool start -> permission request -> idle
function triggerMockExecutionCycle(userPrompt) {
  if (currentTaskTimeout) clearTimeout(currentTaskTimeout);

  broadcastState('thinking');
  broadcastLog(`Parsing instruction: "${userPrompt}"`);

  // Step 1: Tool execution simulation
  currentTaskTimeout = setTimeout(() => {
    // Broadcast tool start event
    activeClients.forEach(c => sendSse(c.res, {
      name: "run_command",
      toolId: "task-1234"
    }));
    broadcastLog("Preparing system diagnostic check...");

    // Step 2: Permission request with a volumetric diff layout simulation
    currentTaskTimeout = setTimeout(() => {
      const mockDiff = [
        "@@ -10,6 +10,12 @@",
        " func main() {",
        "     println(\"Checking engine telemetry...\")",
        "-    val status = checkEngineLegacy()",
        "+    val status = checkEngineSpatial()",
        "+    if (status.isDegraded) {",
        "+        triggerAlarm()",
        "+    }",
        " }"
      ].join("\n");

      activeClients.forEach(c => sendSse(c.res, {
        toolName: "run_command",
        toolUseId: "use-5678",
        description: "Run gradle debug build and update telemetry listener?",
        detail: mockDiff,
        options: [
          { text: "Proceed", primary: true },
          { text: "Cancel", primary: false }
        ]
      }));
      
      // Transition state to awaiting input
      broadcastState('awaiting_input');
    }, 2000);
  }, 1500);
}

server.listen(PORT, () => {
  console.log(`Mock even-terminal daemon listening on http://localhost:${PORT}`);
  console.log(`Configure Android Emulator to connect to 10.0.2.2:${PORT}`);
});
