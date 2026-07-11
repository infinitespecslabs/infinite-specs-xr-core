// 🌀 client.js — Frontend dashboard logic and events

const statusBadge = document.getElementById('status-badge');
const statusText = document.getElementById('status-text');
const deviceIpInput = document.getElementById('device-ip-input');
const configForm = document.getElementById('config-form');
const mockForm = document.getElementById('mock-form');
const jsonDisplay = document.getElementById('json-display');
const terminalBody = document.getElementById('terminal-body');
const clearLogsBtn = document.getElementById('clear-logs-btn');

const nodeObserver = document.getElementById('node-observer');
const nodeAgent = document.getElementById('node-agent');
const nodeCodebase = document.getElementById('node-codebase');
const topologyDiagram = document.getElementById('topology-diagram');
const loopStateIndicator = document.getElementById('loop-state-indicator');

// Connect to local Node server SSE endpoint for updates
let eventSource = null;

function connectSSE() {
  if (eventSource) {
    eventSource.close();
  }

  eventSource = new EventSource('/api/events');

  eventSource.onmessage = (event) => {
    try {
      const payload = JSON.parse(event.data);
      handleServerEvent(payload);
    } catch (e) {
      console.error("Failed to parse SSE payload:", e);
    }
  };

  eventSource.onerror = (err) => {
    console.error("SSE connection error:", err);
    updateStatus('disconnected', 'localhost');
  };
}

function handleServerEvent(event) {
  const { type, data } = event;

  switch (type) {
    case 'init':
      // Setup initial dashboard state
      deviceIpInput.value = data.deviceIp || 'localhost';
      updateStatus(data.connectionStatus, data.deviceIp);
      updateSpecification(data.activeIntent);
      clearLogs();
      if (data.terminalLogs) {
        data.terminalLogs.forEach(log => appendLog(log));
      }
      break;

    case 'status':
      updateStatus(data.connectionStatus, data.deviceIp);
      break;

    case 'specification':
      updateSpecification(data.data || data);
      break;

    case 'log':
      appendLog(data);
      break;

    default:
      console.log("Unhandled event type:", type, data);
  }
}

// Update status badge UI and topology states
function updateStatus(status, ip) {
  // Reset classes
  statusBadge.className = 'status-indicator-badge';
  statusBadge.classList.add(status);
  statusText.textContent = status.toUpperCase();

  if (status === 'connected') {
    nodeObserver.classList.add('active');
    nodeAgent.classList.add('active');
  } else if (status === 'connecting') {
    nodeObserver.classList.add('active');
    nodeAgent.classList.remove('active');
  } else {
    nodeObserver.classList.remove('active');
    nodeAgent.classList.remove('active');
  }
}

// Update active specification JSON display
function updateSpecification(spec) {
  if (!spec) {
    jsonDisplay.textContent = JSON.stringify({
      "status": "awaiting_telemetry",
      "message": "Press 'Engage Perception Pipeline' on Android XR or submit manual intent form"
    }, null, 2);
    return;
  }

  jsonDisplay.textContent = JSON.stringify(spec, null, 2);
}

// Clear local terminal logs
function clearLogs() {
  terminalBody.innerHTML = '';
}

// Append log message to terminal UI
function appendLog(log) {
  const row = document.createElement('div');
  row.className = `terminal-row ${log.type}-row`;

  const ts = document.createElement('span');
  ts.className = 'timestamp';
  ts.textContent = `[${log.timestamp || new Date().toLocaleTimeString()}]`;

  const txt = document.createElement('span');
  txt.className = 'log-text';
  txt.textContent = log.text;

  row.appendChild(ts);
  row.appendChild(txt);
  terminalBody.appendChild(row);

  // Auto-scroll to bottom of terminal
  terminalBody.scrollTop = terminalBody.scrollHeight;

  // Dynamically animate the topology nodes depending on current log stages
  animateTopologyStages(log.text);
}

// Animates elements of the diagram based on output messages
function animateTopologyStages(logText) {
  if (logText.includes('Ingesting')) {
    topologyDiagram.classList.add('active');
    loopStateIndicator.querySelector('span').textContent = 'INGESTING INTENT';
    nodeObserver.classList.add('active');
    nodeAgent.classList.remove('active');
    nodeCodebase.classList.remove('active');
  } else if (logText.includes('Creating simulated agent worktree')) {
    loopStateIndicator.querySelector('span').textContent = 'SYNCING WORKTREE';
    nodeAgent.classList.add('active');
    nodeObserver.classList.remove('active');
  } else if (logText.includes('Generated Kotlin class file')) {
    nodeCodebase.classList.add('active');
  } else if (logText.includes('Starting compilation')) {
    loopStateIndicator.querySelector('span').textContent = 'COMPILING CODE';
    nodeAgent.classList.add('active');
    nodeCodebase.classList.add('active');
  } else if (logText.includes('constraints PASSED') || logText.includes('test scenario matching')) {
    loopStateIndicator.querySelector('span').textContent = 'VERIFYING TESTS';
    nodeObserver.classList.add('active');
    nodeCodebase.classList.add('active');
  } else if (logText.includes('Loop complete') || logText.includes('Closed Loop')) {
    loopStateIndicator.querySelector('span').textContent = 'LOOP CLOSED';
    nodeObserver.classList.add('active');
    nodeAgent.classList.add('active');
    nodeCodebase.classList.add('active');
    
    // Fade topology activity back down after a delay
    setTimeout(() => {
      topologyDiagram.classList.remove('active');
      loopStateIndicator.querySelector('span').textContent = 'LOOP IDLE';
      // Reset back to connection default state
      const isConnected = statusBadge.classList.contains('connected');
      updateStatus(isConnected ? 'connected' : 'disconnected', deviceIpInput.value);
    }, 4000);
  }
}

// Setup Event Listeners
configForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const ip = deviceIpInput.value.trim();

  try {
    const res = await fetch('/api/config', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ ip })
    });
    
    if (!res.ok) throw new Error("Failed to update config");
    console.log("Updated device IP successfully");
  } catch (err) {
    console.error("Error setting configuration:", err);
  }
});

mockForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  
  const nodeType = document.getElementById('mock-node-type').value.trim();
  const anchorId = document.getElementById('mock-anchor-id').value.trim();
  const skill = document.getElementById('mock-skill').value;
  const constraintsText = document.getElementById('mock-constraints').value;
  const constraints = constraintsText.split('\n').map(l => l.trim()).filter(l => l.length > 0);

  try {
    const res = await fetch('/api/mock-trigger', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ nodeType, anchorId, skill, constraints })
    });
    
    if (!res.ok) throw new Error("Mock trigger failed");
    console.log("Mock specification triggered successfully");
  } catch (err) {
    console.error("Error running mock trigger:", err);
  }
});

clearLogsBtn.addEventListener('click', clearLogs);

// Initialize SSE connection on load
connectSSE();
