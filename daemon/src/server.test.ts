import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import request from "supertest";

vi.mock("@anthropic-ai/claude-agent-sdk", () => ({
  query: vi.fn(() => ({
    [Symbol.asyncIterator]: () => ({ next: () => Promise.resolve({ value: undefined, done: true }) }),
  })),
  listSessions: vi.fn(async () => []),
  getSessionMessages: vi.fn(async () => []),
}));

const { buildApp } = await import("./server.js");
import type { AppConfig } from "./config.js";

function baseConfig(overrides: Partial<AppConfig> = {}): AppConfig {
  return { port: 3456, token: "test-token", cwd: "/project", verbose: false, ...overrides };
}

describe("buildApp", () => {
  let consoleLogSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleLogSpy = vi.spyOn(console, "log").mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("rejects unauthenticated requests to core routes", async () => {
    const app = buildApp(baseConfig());
    const res = await request(app).get("/api/sessions");
    expect(res.status).toBe(401);
  });

  it("rejects unauthenticated requests to the events route", async () => {
    const app = buildApp(baseConfig());
    const res = await request(app).get("/api/events?sessionId=s1");
    expect(res.status).toBe(401);
  });

  it("serves core routes for an authenticated request", async () => {
    const app = buildApp(baseConfig());
    const res = await request(app).get("/api/sessions").set("Authorization", "Bearer test-token");
    expect(res.status).toBe(200);
    expect(res.body).toEqual({ sessions: [] });
  });

  it("parses JSON request bodies", async () => {
    const app = buildApp(baseConfig());
    const res = await request(app)
      .post("/api/prompt")
      .set("Authorization", "Bearer test-token")
      .send({}); // no 'text' field — proves the body was parsed and reached route validation
    expect(res.status).toBe(400);
    expect(res.body.error).toBe("Missing 'text' field");
  });

  it("enables CORS", async () => {
    const app = buildApp(baseConfig());
    const res = await request(app).get("/api/sessions").set("Authorization", "Bearer test-token").set("Origin", "http://example.com");
    expect(res.headers["access-control-allow-origin"]).toBe("*");
  });

  it("logs request timing when verbose is enabled", async () => {
    const app = buildApp(baseConfig({ verbose: true }));
    await request(app).get("/api/sessions").set("Authorization", "Bearer test-token");
    expect(consoleLogSpy.mock.calls.some((call: unknown[]) => String(call[0]).includes("GET /api/sessions"))).toBe(true);
  });

  it("does not log request timing when verbose is disabled", async () => {
    const app = buildApp(baseConfig({ verbose: false }));
    await request(app).get("/api/sessions").set("Authorization", "Bearer test-token");
    expect(consoleLogSpy.mock.calls.some((call: unknown[]) => String(call[0]).includes("GET /api/sessions"))).toBe(false);
  });
});
