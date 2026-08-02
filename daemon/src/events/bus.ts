import type { Response } from "express";
import type { SseEvent } from "../types.js";
import { debugLog, warnLog } from "../logger.js";

// Per-session ring buffer + SSE broadcaster. Mirrors even-terminal's
// dist/routes/events.js design exactly (docs/SYSTEM_DESIGN.md §2.1) — the
// Kotlin client already assumes this replay/heartbeat behavior.

const MAX_MESSAGES_PER_SESSION = 500;
const HEARTBEAT_INTERVAL_MS = 15000;

interface StoredMessage {
  id: number;
  msg: SseEvent;
}

interface SessionChannel {
  messages: StoredMessage[];
  clients: Set<Response>;
  nextId: number;
}

const sessions = new Map<string, SessionChannel>();

function getChannel(sessionId: string): SessionChannel {
  let channel = sessions.get(sessionId);
  if (!channel) {
    channel = { messages: [], clients: new Set(), nextId: 1 };
    sessions.set(sessionId, channel);
  }
  return channel;
}

export function pushMessage(sessionId: string, msg: SseEvent): number {
  const channel = getChannel(sessionId);
  const id = channel.nextId++;
  channel.messages.push({ id, msg });
  if (channel.messages.length > MAX_MESSAGES_PER_SESSION) {
    channel.messages.shift();
  }
  return id;
}

export function getMessages(sessionId: string, after: number): ({ id: number } & SseEvent)[] {
  const channel = sessions.get(sessionId);
  if (!channel) return [];
  return channel.messages.filter((m) => m.id > after).map((m) => ({ id: m.id, ...m.msg }));
}

export function broadcast(sessionId: string, msg: SseEvent, id: number): void {
  const channel = sessions.get(sessionId);
  const data = JSON.stringify(msg);
  debugLog(`[SSE-${sessionId}]:`, data);
  if (!channel || channel.clients.size === 0) return;
  let deadCount = 0;
  for (const res of channel.clients) {
    try {
      res.write(`id: ${id}\ndata: ${data}\n\n`);
    } catch {
      channel.clients.delete(res);
      deadCount++;
    }
  }
  if (deadCount > 0) {
    warnLog(`[sse] Removed ${deadCount} dead client(s) for session=${sessionId} (remaining: ${channel.clients.size})`);
  }
}

/** Combined push + broadcast — the helper ClaudeSession/ClaudeProvider call on every event. */
export function emit(sessionId: string | undefined, msg: SseEvent): void {
  if (!sessionId) return;
  const id = pushMessage(sessionId, msg);
  broadcast(sessionId, msg, id);
}

export function clientCount(): number {
  let total = 0;
  for (const channel of sessions.values()) total += channel.clients.size;
  return total;
}

export function registerClient(sessionId: string, res: Response, needReplay: boolean): void {
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  res.setHeader("X-Accel-Buffering", "no");
  res.flushHeaders();
  res.write(":ok\n\n");

  const channel = getChannel(sessionId);
  if (needReplay && channel.messages.length > 0) {
    for (const entry of channel.messages) {
      res.write(`id: ${entry.id}\ndata: ${JSON.stringify(entry.msg)}\n\n`);
    }
  }

  channel.clients.add(res);
  debugLog(`[sse] Client connected session=${sessionId} (session clients: ${channel.clients.size}, total: ${clientCount()})`);

  const heartbeat = setInterval(() => {
    try {
      res.write(":heartbeat\n\n");
    } catch {
      clearInterval(heartbeat);
      channel.clients.delete(res);
    }
  }, HEARTBEAT_INTERVAL_MS);

  res.req.on("close", () => {
    clearInterval(heartbeat);
    channel.clients.delete(res);
    debugLog(`[sse] Client disconnected (total: ${clientCount()})`);
  });
}
