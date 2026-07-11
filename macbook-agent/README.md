# 🌀 Strange Loop Agent Daemon & Dashboard

This directory houses the Macbook Agent Loop simulator, designed to interface directly with the **Infinite Specs XR** application running on Android XR smart glasses or an emulator. 

It establishes a local Model Context Protocol (MCP) spec subscriber loop, ingests spatial specifications, compiles generated mock-classes in the workspace, runs tests, and streams compilation telemetry back onto the smart glasses HUD in real-time.

---

## 🛠️ Architecture Overview

The system operates as a closed-loop cybernetic feedback system:
1. **The Human Observer (Android XR App):** Triggers the perception pipeline to capture gaze vectors and vocal transcripts (e.g. *"Declare a KafkaConsumer targeting rig left"*).
2. **The Specification Daemon (Ktor HTTP/SSE server):** The app structures this intent using Google Gemini and serves it on `http://<device_ip>:8080/mcp/sse`.
3. **The Worktree Agent (This Macbook Daemon):** Subscribes to the Ktor SSE stream, receives the specification, writes Kotlin files locally, simulates a compile/test pipeline, and POSTs live build logs back to `http://<device_ip>:8080/mcp/logs` to update the HUD.

---

## 🚀 Getting Started

### 1. Prerequisites
Ensure you have **Node.js** (v18+) and **npm** installed on your workstation.

### 2. Installation
Install the required micro-dependencies (Express + CORS):
```bash
npm install
```

### 3. Execution
Start the local server daemon:
```bash
npm start
```
The server will start on port `3000`. Open your browser and navigate to:
👉 **[http://localhost:3000](http://localhost:3000)**

---

## 📡 Connecting to Android XR

### Scenario A: Running in the Android Emulator
If the Infinite Specs XR app is running locally in the Android XR Emulator:
1. Make sure the emulator is active.
2. Run port forwarding via ADB to link ports:
   ```bash
   adb forward tcp:8080 tcp:8080
   ```
3. On the Macbook Dashboard (`localhost:3000`), keep the target IP set as `localhost` (this routes to port `8080` on your host).
4. Handshake logs will appear in the dashboard log and on the Android HUD.

### Scenario B: Running on a Physical Eyewear Device
If running on real hardware:
1. Ensure both your Macbook and the Android XR glasses are on the same Wi-Fi network.
2. Identify the IP address of your glasses (e.g. `192.168.1.45`).
3. Enter this IP in the **XR Device Connectivity** panel on the Macbook dashboard and click **Update IP & Reconnect**.
4. The Agent Daemon will automatically establish a connection to the glasses and stream status.

---

## 🧪 Simulation & Verification (Offline Testing)

If you don't have the Android XR application running:
1. Open the dashboard at `http://localhost:3000`.
2. Locate the **Offline Manual Intent Trigger** form on the left.
3. Fill in custom node parameters or use defaults, and click **Inject Spatial Specification**.
4. You will immediately see the active specification update, the topology diagram cycle through compilation states (Observer -> Agent -> Workspace), and real-time compilation logs print in the terminal log.
5. The agent will write a physical Kotlin file under `simulated-agent-worktree/` matching your parameters.
