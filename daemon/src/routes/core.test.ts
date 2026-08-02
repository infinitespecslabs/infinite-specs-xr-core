import { beforeEach, describe, expect, it, vi } from "vitest";
import express from "express";
import request from "supertest";
import { createCoreRouter } from "./core.js";
import { pushMessage } from "../events/bus.js";
import type { ClaudeProvider } from "../claude/provider.js";

function createMockProvider(): ClaudeProvider {
  return {
    prompt: vi.fn(async (sessionId: string | undefined) => ({ sessionId: sessionId ?? "new-session-id" })),
    respondPermission: vi.fn(),
    respondQuestion: vi.fn(),
    interrupt: vi.fn(),
    getStatus: vi.fn(() => null),
    listSessions: vi.fn(async () => []),
    getInfo: vi.fn(async () => ({ account: {}, model: "Claude Opus 4.6", version: "1.0.0", provider: "claude" as const })),
    getHistory: vi.fn(async () => []),
  };
}

function buildApp(provider: ClaudeProvider) {
  const app = express();
  app.use(express.json());
  app.use("/api", createCoreRouter(provider));
  return app;
}

describe("core router", () => {
  let provider: ClaudeProvider;
  let app: ReturnType<typeof buildApp>;

  beforeEach(() => {
    provider = createMockProvider();
    app = buildApp(provider);
  });

  describe("provider guard", () => {
    it("rejects an unsupported ?provider= query param", async () => {
      const res = await request(app).get("/api/sessions?provider=codex");
      expect(res.status).toBe(400);
      expect(res.body.error).toContain('Unsupported provider "codex"');
    });

    it("rejects an unsupported provider in the request body", async () => {
      const res = await request(app).post("/api/prompt").send({ text: "hi", provider: "codex" });
      expect(res.status).toBe(400);
    });

    it("allows requests with no provider specified", async () => {
      const res = await request(app).get("/api/sessions");
      expect(res.status).toBe(200);
    });

    it("allows requests explicitly specifying provider=claude", async () => {
      const res = await request(app).get("/api/sessions?provider=claude");
      expect(res.status).toBe(200);
    });
  });

  describe("GET /api/sessions", () => {
    it("returns the provider's session list", async () => {
      (provider.listSessions as ReturnType<typeof vi.fn>).mockResolvedValue([
        { id: "s1", title: "t", timestamp: "now", cwd: "/x", provider: "claude", status: null },
      ]);
      const res = await request(app).get("/api/sessions");
      expect(res.status).toBe(200);
      expect(res.body.sessions).toHaveLength(1);
    });

    it("defaults limit to 10 and parses an explicit limit", async () => {
      await request(app).get("/api/sessions");
      expect(provider.listSessions).toHaveBeenCalledWith(10, undefined);

      await request(app).get("/api/sessions?limit=25");
      expect(provider.listSessions).toHaveBeenCalledWith(25, undefined);
    });

    it("passes cwd through from the query string", async () => {
      await request(app).get("/api/sessions?cwd=/my/project");
      expect(provider.listSessions).toHaveBeenCalledWith(10, "/my/project");
    });

    it("returns an empty list with an error message when the provider throws", async () => {
      (provider.listSessions as ReturnType<typeof vi.fn>).mockRejectedValue(new Error("boom"));
      const res = await request(app).get("/api/sessions");
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ sessions: [], error: "boom" });
    });
  });

  describe("GET /api/info", () => {
    it("returns the provider's info", async () => {
      const res = await request(app).get("/api/info");
      expect(res.status).toBe(200);
      expect(res.body.model).toBe("Claude Opus 4.6");
    });

    it("falls back to Unknown fields when the provider throws", async () => {
      (provider.getInfo as ReturnType<typeof vi.fn>).mockRejectedValue(new Error("no claude cli"));
      const res = await request(app).get("/api/info");
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ account: {}, model: "Unknown", version: "Unknown", provider: "claude", error: "no claude cli" });
    });
  });

  describe("GET /api/update-check", () => {
    it("always reports no update available", async () => {
      const res = await request(app).get("/api/update-check");
      expect(res.status).toBe(200);
      expect(res.body.updateAvailable).toBe(false);
      expect(res.body.currentVersion).toBe(res.body.latestVersion);
    });
  });

  describe("POST /api/prompt", () => {
    it("rejects a request with no text", async () => {
      const res = await request(app).post("/api/prompt").send({});
      expect(res.status).toBe(400);
      expect(res.body.error).toBe("Missing 'text' field");
    });

    it("accepts a prompt and returns 202 with the session id", async () => {
      const res = await request(app).post("/api/prompt").send({ text: "do a thing" });
      expect(res.status).toBe(202);
      expect(res.body).toEqual({ ok: true, sessionId: "new-session-id", provider: "claude" });
    });

    it("passes sessionId and cwd through to the provider", async () => {
      await request(app).post("/api/prompt").send({ text: "hi", sessionId: "s1", cwd: "/project" });
      expect(provider.prompt).toHaveBeenCalledWith("s1", "hi", "/project");
    });

    it("uses the error's statusCode when the provider throws one", async () => {
      const err = Object.assign(new Error("not found"), { statusCode: 404 });
      (provider.prompt as ReturnType<typeof vi.fn>).mockRejectedValue(err);
      const res = await request(app).post("/api/prompt").send({ text: "hi" });
      expect(res.status).toBe(404);
      expect(res.body.error).toBe("not found");
    });

    it("defaults to 500 when the provider throws without a statusCode", async () => {
      (provider.prompt as ReturnType<typeof vi.fn>).mockRejectedValue(new Error("unexpected"));
      const res = await request(app).post("/api/prompt").send({ text: "hi" });
      expect(res.status).toBe(500);
    });
  });

  describe("POST /api/permission-response", () => {
    it("rejects a request with no sessionId", async () => {
      const res = await request(app).post("/api/permission-response").send({ decision: "allow" });
      expect(res.status).toBe(400);
    });

    it("404s for an unknown session", async () => {
      const res = await request(app).post("/api/permission-response").send({ sessionId: "ghost", decision: "allow" });
      expect(res.status).toBe(404);
    });

    it("forwards the decision to the provider, defaulting to deny", async () => {
      (provider.getStatus as ReturnType<typeof vi.fn>).mockReturnValue({ state: "awaiting" });
      let res = await request(app).post("/api/permission-response").send({ sessionId: "s1", decision: "allowAlways" });
      expect(res.status).toBe(200);
      expect(provider.respondPermission).toHaveBeenCalledWith("s1", "allowAlways");

      res = await request(app).post("/api/permission-response").send({ sessionId: "s1" });
      expect(provider.respondPermission).toHaveBeenCalledWith("s1", "deny");
    });
  });

  describe("POST /api/question-response", () => {
    it("404s for an unknown session", async () => {
      const res = await request(app).post("/api/question-response").send({ sessionId: "ghost", answer: "x" });
      expect(res.status).toBe(404);
    });

    it("forwards the answer to the provider, defaulting to skip", async () => {
      (provider.getStatus as ReturnType<typeof vi.fn>).mockReturnValue({ state: "awaiting" });
      await request(app).post("/api/question-response").send({ sessionId: "s1", answer: "Spaces" });
      expect(provider.respondQuestion).toHaveBeenCalledWith("s1", "Spaces");

      await request(app).post("/api/question-response").send({ sessionId: "s1" });
      expect(provider.respondQuestion).toHaveBeenCalledWith("s1", "skip");
    });
  });

  describe("POST /api/interrupt", () => {
    it("404s for an unknown session", async () => {
      const res = await request(app).post("/api/interrupt").send({ sessionId: "ghost" });
      expect(res.status).toBe(404);
    });

    it("interrupts a known session", async () => {
      (provider.getStatus as ReturnType<typeof vi.fn>).mockReturnValue({ state: "busy" });
      const res = await request(app).post("/api/interrupt").send({ sessionId: "s1" });
      expect(res.status).toBe(200);
      expect(provider.interrupt).toHaveBeenCalledWith("s1");
    });
  });

  describe("GET /api/status", () => {
    it("rejects a request with no sessionId", async () => {
      const res = await request(app).get("/api/status");
      expect(res.status).toBe(400);
    });

    it("404s for an unknown session", async () => {
      const res = await request(app).get("/api/status?sessionId=ghost");
      expect(res.status).toBe(404);
    });

    it("returns the session's state", async () => {
      (provider.getStatus as ReturnType<typeof vi.fn>).mockReturnValue({ state: "busy" });
      const res = await request(app).get("/api/status?sessionId=s1");
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ state: "busy", sessionId: "s1", provider: "claude" });
    });
  });

  describe("GET /api/messages", () => {
    it("rejects a request with no sessionId", async () => {
      const res = await request(app).get("/api/messages");
      expect(res.status).toBe(400);
    });

    it("returns buffered messages after the given id", async () => {
      const sessionId = "messages-route-session";
      pushMessage(sessionId, { type: "text_delta", text: "a" });
      pushMessage(sessionId, { type: "text_delta", text: "b" });
      (provider.getStatus as ReturnType<typeof vi.fn>).mockReturnValue({ state: "busy" });

      const res = await request(app).get(`/api/messages?sessionId=${sessionId}&after=1`);
      expect(res.status).toBe(200);
      expect(res.body).toEqual({
        messages: [{ id: 2, type: "text_delta", text: "b" }],
        state: "busy",
        sessionId,
        provider: "claude",
      });
    });

    it("falls back to idle state and a null provider for an unknown session", async () => {
      const res = await request(app).get("/api/messages?sessionId=unknown-session");
      expect(res.status).toBe(200);
      expect(res.body.state).toBe("idle");
      expect(res.body.provider).toBeNull();
    });
  });

  describe("GET /api/sessions/:id/history", () => {
    it("returns the provider's history", async () => {
      (provider.getHistory as ReturnType<typeof vi.fn>).mockResolvedValue([{ role: "user", text: "hi" }]);
      const res = await request(app).get("/api/sessions/s1/history");
      expect(res.status).toBe(200);
      expect(res.body.history).toEqual([{ role: "user", text: "hi" }]);
    });

    it("caps the requested limit at 10", async () => {
      await request(app).get("/api/sessions/s1/history?limit=999");
      expect(provider.getHistory).toHaveBeenCalledWith("s1", 10);
    });

    it("returns an empty history with an error message when the provider throws", async () => {
      (provider.getHistory as ReturnType<typeof vi.fn>).mockRejectedValue(new Error("no such session"));
      const res = await request(app).get("/api/sessions/s1/history");
      expect(res.status).toBe(200);
      expect(res.body).toEqual({ history: [], error: "no such session" });
    });
  });
});
