import { createRequire } from "node:module";
import { Router } from "express";
import type { ClaudeProvider } from "../claude/provider.js";
import { getMessages } from "../events/bus.js";
import { log, warnLog } from "../logger.js";
import type {
  InterruptRequestBody,
  MessagesResponse,
  PermissionResponseBody,
  PromptRequestBody,
  QuestionResponseBody,
  StatusResponse,
  UpdateCheckResponse,
} from "../types.js";

const require = createRequire(import.meta.url);
const pkg = require("../../package.json") as { version: string };

export function createCoreRouter(provider: ClaudeProvider): Router {
  const router = Router();

  // Claude-only daemon: still validate ?provider=/body provider so the wire
  // contract stays compatible with a future second provider (docs/SYSTEM_DESIGN.md §2).
  router.use((req, res, next) => {
    for (const value of [req.query.provider, (req.body as { provider?: string } | undefined)?.provider]) {
      if (value !== undefined && value !== "claude") {
        res.status(400).json({ error: `Unsupported provider "${String(value)}". Supported providers: claude` });
        return;
      }
    }
    next();
  });

  // GET /api/sessions — list resumable sessions
  router.get("/sessions", async (req, res) => {
    const cwd = (req.query.cwd as string | undefined) ?? process.env.PROJECT_DIR;
    const limit = Number(req.query.limit) || 10;
    try {
      const sessions = await provider.listSessions(limit, cwd);
      res.json({ sessions });
    } catch (err) {
      res.json({ sessions: [], error: (err as Error).message });
    }
  });

  // GET /api/info — account, model, version
  router.get("/info", async (_req, res) => {
    try {
      res.json(await provider.getInfo());
    } catch (err) {
      res.json({ account: {}, model: "Unknown", version: "Unknown", provider: "claude", error: (err as Error).message });
    }
  });

  // GET /api/update-check — stubbed: this is our own daemon, not a versioned
  // npm package with a registry to check against (docs/SYSTEM_DESIGN.md §2.2).
  router.get("/update-check", (_req, res) => {
    const response: UpdateCheckResponse = { currentVersion: pkg.version, latestVersion: pkg.version, updateAvailable: false };
    res.json(response);
  });

  // POST /api/prompt — send a prompt to a session (create if needed)
  router.post("/prompt", async (req, res) => {
    const { text, sessionId, cwd } = (req.body ?? {}) as PromptRequestBody;
    log(`[prompt] sessionId=${sessionId ?? "(none)"} text=${(text || "").slice(0, 80)}`);
    if (!text || typeof text !== "string") {
      warnLog("[prompt] rejected: missing text field");
      res.status(400).json({ error: "Missing 'text' field" });
      return;
    }
    try {
      const result = await provider.prompt(sessionId, text, cwd);
      res.status(202).json({ ok: true, sessionId: result.sessionId, provider: "claude" });
    } catch (err) {
      const error = err as Error & { statusCode?: number };
      warnLog(`[prompt] failed: ${error.message}`);
      res.status(typeof error.statusCode === "number" ? error.statusCode : 500).json({ error: error.message });
    }
  });

  // POST /api/permission-response
  router.post("/permission-response", (req, res) => {
    const { sessionId, decision } = (req.body ?? {}) as PermissionResponseBody;
    if (!sessionId) {
      res.status(400).json({ error: "Missing 'sessionId'" });
      return;
    }
    if (!provider.getStatus(sessionId)) {
      res.status(404).json({ error: "Session not found" });
      return;
    }
    provider.respondPermission(sessionId, decision || "deny");
    res.json({ ok: true });
  });

  // POST /api/question-response
  router.post("/question-response", (req, res) => {
    const { sessionId, answer } = (req.body ?? {}) as QuestionResponseBody;
    if (!sessionId) {
      res.status(400).json({ error: "Missing 'sessionId'" });
      return;
    }
    if (!provider.getStatus(sessionId)) {
      res.status(404).json({ error: "Session not found" });
      return;
    }
    provider.respondQuestion(sessionId, answer || "skip");
    res.json({ ok: true });
  });

  // POST /api/interrupt
  router.post("/interrupt", (req, res) => {
    const { sessionId } = (req.body ?? {}) as InterruptRequestBody;
    if (!sessionId) {
      res.status(400).json({ error: "Missing 'sessionId'" });
      return;
    }
    if (!provider.getStatus(sessionId)) {
      res.status(404).json({ error: "Session not found" });
      return;
    }
    provider.interrupt(sessionId);
    res.json({ ok: true });
  });

  // GET /api/status
  router.get("/status", (req, res) => {
    const sessionId = req.query.sessionId as string | undefined;
    if (!sessionId) {
      res.status(400).json({ error: "Missing 'sessionId'" });
      return;
    }
    const status = provider.getStatus(sessionId);
    if (!status) {
      res.status(404).json({ error: "Session not found" });
      return;
    }
    const response: StatusResponse = { state: status.state as StatusResponse["state"], sessionId, provider: "claude" };
    res.json(response);
  });

  // GET /api/messages?sessionId=&after= — SSE reconnect gap-fill
  router.get("/messages", (req, res) => {
    const after = parseInt(req.query.after as string, 10) || 0;
    const sessionId = req.query.sessionId as string | undefined;
    if (!sessionId) {
      res.status(400).json({ error: "Missing 'sessionId'" });
      return;
    }
    const status = provider.getStatus(sessionId);
    const messages = getMessages(sessionId, after);
    const response: MessagesResponse = { messages, state: status?.state ?? "idle", sessionId, provider: status ? "claude" : null };
    res.json(response);
  });

  // GET /api/sessions/:id/history
  router.get("/sessions/:id/history", async (req, res) => {
    const id = req.params.id;
    const limit = Math.min(parseInt(req.query.limit as string, 10) || 10, 10);
    try {
      const history = await provider.getHistory(id, limit);
      res.json({ history });
    } catch (err) {
      res.json({ history: [], error: (err as Error).message });
    }
  });

  return router;
}
