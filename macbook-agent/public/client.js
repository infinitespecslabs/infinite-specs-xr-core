// 🌀 client.js — Terminal Mode frontend dashboard controller

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

const interactivePromptCard = document.getElementById('interactive-prompt-card');
const promptMessageText = document.getElementById('prompt-message-text');
const promptOptionsContainer = document.getElementById('prompt-options-container');

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
    updateStatus('disconnected', 'localhost', 'OFFLINE');
  };
}

function handleServerEvent(event) {
  const { type, data } = event;

  switch (type) {
    case 'init':
      deviceIpInput.value = data.deviceIp || 'localhost';
      updateStatus(data.connectionStatus, data.deviceIp, data.currentAgentState);
      updateSpecification(data.activeIntent);
      clearLogs();
      if (data.terminalLogs) {
        data.terminalLogs.forEach(log => appendLog(log));
      }
      break;

    case 'status':
      updateStatus(data.connectionStatus, data.deviceIp, data.currentAgentState, data.prompt, data.options);
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

// Update connectivity status badge, and sync current agent state
function updateStatus(status, ip, agentState, prompt = '', options = []) {
  statusBadge.className = 'status-indicator-badge';
  
  // Set badge layout style based on connectivity or agent state
  if (agentState === 'AWAITING_INPUT') {
    statusBadge.classList.add('connecting'); // Amber warning color
    statusText.textContent = `AWAITING INPUT`;
  } else if (agentState === 'THINKING' || agentState === 'EXECUTING') {
    statusBadge.classList.add('connected'); // Green active color
    statusText.textContent = agentState;
  } else if (status === 'connected') {
    statusBadge.classList.add('connected');
    statusText.textContent = `PAIRED`;
  } else if (status === 'connecting') {
    statusBadge.classList.add('connecting');
    statusText.textContent = `CONNECTING`;
  } else {
    statusBadge.classList.add('disconnected');
    statusText.textContent = `DISCONNECTED`;
  }

  // Manage interactive inputs card visibility
  if (agentState === 'AWAITING_INPUT' && options && options.length > 0) {
    showPromptOverlay(prompt, options);
  } else {
    hidePromptOverlay();
  }

  // Manage node activations
  updateNodeTopology(agentState, status);
}

function updateNodeTopology(agentState, connStatus) {
  // Clear classes
  nodeObserver.classList.remove('active');
  nodeAgent.classList.remove('active');
  nodeCodebase.classList.remove('active');
  topologyDiagram.classList.remove('active');

  const isConnected = (connStatus === 'connected');

  switch (agentState) {
    case 'THINKING':
      topologyDiagram.classList.add('active');
      loopStateIndicator.querySelector('span').textContent = 'INGESTING SPECTRA';
      nodeObserver.classList.add('active');
      nodeAgent.classList.add('active');
      break;
      
    case 'EXECUTING':
      topologyDiagram.classList.add('active');
      loopStateIndicator.querySelector('span').textContent = 'COMPILING CODE';
      nodeAgent.classList.add('active');
      nodeCodebase.classList.add('active');
      break;
      
    case 'AWAITING_INPUT':
      topologyDiagram.classList.add('active');
      loopStateIndicator.querySelector('span').textContent = 'AWAITING CHOICE';
      nodeObserver.classList.add('active');
      break;
      
    case 'SUCCESS':
      topologyDiagram.classList.add('active');
      loopStateIndicator.querySelector('span').textContent = 'LOOP SUCCESS';
      nodeObserver.classList.add('active');
      nodeAgent.classList.add('active');
      nodeCodebase.classList.add('active');
      break;

    case 'IDLE':
    default:
      loopStateIndicator.querySelector('span').textContent = isConnected ? 'MIRROR PAIR ACTIVE' : 'LOOP IDLE';
      if (isConnected) {
        nodeObserver.classList.add('active');
        nodeAgent.classList.add('active');
      }
      break;
  }
}

// Display option chips for user selection
function showPromptOverlay(prompt, options) {
  promptMessageText.textContent = prompt || "Choose footprint config:";
  promptOptionsContainer.innerHTML = '';

  options.forEach(option => {
    const chip = document.createElement('button');
    chip.className = 'prompt-option-chip';
    chip.textContent = option;
    chip.addEventListener('click', () => submitOptionInput(option));
    promptOptionsContainer.appendChild(chip);
  });

  interactivePromptCard.classList.remove('hidden');
}

function hidePromptOverlay() {
  interactivePromptCard.classList.add('hidden');
}

// Submit option select back to Macbook Agent
async function submitOptionInput(option) {
  hidePromptOverlay();
  try {
    const res = await fetch('/api/agent-input', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ selectedOption: option })
    });
    if (!res.ok) throw new Error("Input submission failed");
    console.log(`Option "${option}" submitted successfully`);
  } catch (err) {
    console.error("Error submitting user input:", err);
  }
}

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

function clearLogs() {
  terminalBody.innerHTML = '';
}

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

  terminalBody.scrollTop = terminalBody.scrollHeight;
}

// Event Listeners
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
  } catch (err) {
    console.error("Error running mock trigger:", err);
  }
});

clearLogsBtn.addEventListener('click', clearLogs);

connectSSE();
