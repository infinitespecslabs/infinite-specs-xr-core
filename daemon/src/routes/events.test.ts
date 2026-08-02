import { beforeEach, describe, expect, it, vi } from "vitest";
import express from "express";
import request from "supertest";
import type { Response } from "express";

const registerClientMock = vi.fn((_sessionId: string, res: Response, _needReplay: boolean) => {
  // registerClient normally keeps an SSE connection open forever; end it here
  // so the test request can complete and we can inspect the call arguments.
  res.status(200).end();
});
vi.mock("../events/bus.js", () => ({
  registerClient: (...args: [string, Response, boolean]) => registerClientMock(...args),
}));

const { default: eventsRouter } = await import("./events.js");

function buildApp() {
  const app = express();
  app.use("/api", eventsRouter);
  return app;
}

describe("events route", () => {
  let app: ReturnType<typeof buildApp>;

  beforeEach(() => {
    registerClientMock.mockClear();
    app = buildApp();
  });

  it("rejects a request with no sessionId", async () => {
    const res = await request(app).get("/api/events");
    expect(res.status).toBe(400);
    expect(res.body).toEqual({ error: "Missing 'sessionId' query parameter" });
    expect(registerClientMock).not.toHaveBeenCalled();
  });

  it("registers the client with the given sessionId and needReplay=false by default", async () => {
    await request(app).get("/api/events?sessionId=s1");
    expect(registerClientMock).toHaveBeenCalledWith("s1", expect.anything(), false);
  });

  it("passes needReplay=true when requested", async () => {
    await request(app).get("/api/events?sessionId=s1&needReplay=true");
    expect(registerClientMock).toHaveBeenCalledWith("s1", expect.anything(), true);
  });

  it("treats any non-'true' needReplay value as false", async () => {
    await request(app).get("/api/events?sessionId=s1&needReplay=yes");
    expect(registerClientMock).toHaveBeenCalledWith("s1", expect.anything(), false);
  });
});
