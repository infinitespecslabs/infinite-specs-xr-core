import { exec } from "node:child_process";
import { promisify } from "node:util";
import { getSessionInfo, getSessionMessages, listSessions } from "@anthropic-ai/claude-agent-sdk";
import { ClaudeSession } from "./session.js";
import type { InfoResponse, SessionInfo, SseEvent } from "../types.js";

const execAsync = promisify(exec);
const MAX_HISTORY_ITEMS = 10;

export interface ClaudeProvider {
  prompt(sessionId: string | undefined, text: string, cwd: string | undefined): Promise<{ sessionId: string }>;
  respondPermission(sessionId: string, decision: string): void;
  respondQuestion(sessionId: string, answer: string): void;
  interrupt(sessionId: string): void;
  getStatus(sessionId: string): { state: string } | null;
  listSessions(limit: number, cwd?: string): Promise<SessionInfo[]>;
  getInfo(): Promise<InfoResponse>;
  getHistory(sessionId: string, limit: number): Promise<{ role: string; text: string }[]>;
}

export function createClaudeProvider(emit: (sessionId: string | undefined, msg: SseEvent) => void): ClaudeProvider {
  const sessions = new Map<string, ClaudeSession>();

  // Only ever called from prompt() after confirming sessionId isn't already
  // in `sessions`, so it always creates fresh — no need to re-check here.
  function makeSession(sessionId: string | undefined): ClaudeSession {
    const session = new ClaudeSession(emit);
    session.onIdReady((sid) => {
      if (!sessions.has(sid)) sessions.set(sid, session);
    });
    if (sessionId) sessions.set(sessionId, session);
    return session;
  }

  async function prompt(sessionId: string | undefined, text: string, cwd: string | undefined): Promise<{ sessionId: string }> {
    let session = sessionId ? sessions.get(sessionId) : undefined;
    if (!session) {
      session = makeSession(sessionId);
      // Resuming an existing session with no cwd from the client: without
      // this, ClaudeSession.start() falls back to the daemon's own
      // process.cwd(), which almost never matches the session's actual
      // project directory and makes the SDK's resume fail with
      // "No conversation found" (see issue #17). Look up the session's real
      // cwd instead of trusting the (usually absent) client-supplied one.
      let resolvedCwd = cwd;
      if (sessionId && !resolvedCwd) {
        const info = await getSessionInfo(sessionId).catch(() => undefined);
        resolvedCwd = info?.cwd;
      }
      await session.start(sessionId, resolvedCwd);
    }

    session.onIdReady((sid) => emit(sid, { type: "user_prompt", text }));

    if (session.busy) {
      session.enqueue(text);
    } else {
      session.run(text).catch((err: Error) => {
        console.error(`[claude-provider] run failed: ${err.message}`);
      });
    }

    const resolvedId = session.id ?? (await session.waitForId(10000).catch(() => null)) ?? "";
    return { sessionId: resolvedId };
  }

  function respondPermission(sessionId: string, decision: string): void {
    sessions.get(sessionId)?.respondPermission(decision);
  }

  function respondQuestion(sessionId: string, answer: string): void {
    sessions.get(sessionId)?.respondQuestion(answer);
  }

  function interrupt(sessionId: string): void {
    sessions.get(sessionId)?.interrupt();
  }

  function getStatus(sessionId: string): { state: string } | null {
    const session = sessions.get(sessionId);
    if (!session) return null;
    return { state: session.status };
  }

  async function listClaudeSessions(limit: number, cwd?: string): Promise<SessionInfo[]> {
    const infos = await listSessions(cwd ? { dir: cwd, limit } : { limit });
    return infos.map((info) => ({
      id: info.sessionId,
      title: (info.customTitle || info.summary || info.firstPrompt || "").slice(0, 64),
      timestamp: new Date(info.lastModified).toISOString(),
      cwd: info.cwd || "",
      provider: "claude",
      status: null,
    }));
  }

  async function getInfo(): Promise<InfoResponse> {
    let version = "";
    try {
      const { stdout } = await execAsync("claude --version", { timeout: 3000 });
      version = stdout.trim().replace(" (Claude Code)", "");
    } catch {
      // Non-fatal — surfaced as "Unknown" below.
    }

    let account: InfoResponse["account"] = {};
    try {
      const { stdout } = await execAsync("claude auth status", { timeout: 5000 });
      const auth = JSON.parse(stdout.trim());
      account = { email: auth.email ?? "", organization: auth.orgName ?? "", subscriptionType: auth.subscriptionType ?? "" };
    } catch {
      // Non-fatal — account stays empty.
    }

    return { account, model: "Claude Opus 4.6", version: version || "Unknown", provider: "claude" };
  }

  async function getHistory(sessionId: string, limit: number): Promise<{ role: string; text: string }[]> {
    const messages = await getSessionMessages(sessionId);
    const reduced: { role: string; text: string }[] = [];
    for (const msg of messages) {
      const contents = (msg.message as { content?: { type: string; text?: string }[] } | undefined)?.content;
      if (!Array.isArray(contents)) continue;
      for (const content of contents) {
        if (content?.type === "text" && content.text) {
          reduced.push({ role: msg.type, text: content.text });
        }
      }
    }
    const returnCount = Math.min(limit, MAX_HISTORY_ITEMS);
    return reduced.slice(-returnCount);
  }

  return {
    prompt,
    respondPermission,
    respondQuestion,
    interrupt,
    getStatus,
    listSessions: listClaudeSessions,
    getInfo,
    getHistory,
  };
}
