import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { broadcast, clientCount, emit, getMessages, pushMessage, registerClient } from "./bus.js";
import type { SseEvent } from "../types.js";

// The module holds a single process-wide session Map with no reset hook, so
// every test uses its own unique session ID to avoid cross-test bleed.
let sessionCounter = 0;
function uniqueSessionId(): string {
  sessionCounter += 1;
  return `session-${sessionCounter}`;
}

interface MockResponse {
  headers: Record<string, string>;
  written: string[];
  headersFlushed: boolean;
  failNextWrite: boolean;
  write: (chunk: string) => boolean;
  setHeader: (name: string, value: string) => void;
  flushHeaders: () => void;
  req: { on: (event: string, cb: () => void) => void; emitClose: () => void };
}

function createMockResponse(): MockResponse {
  let closeHandler: (() => void) | undefined;
  const res: MockResponse = {
    headers: {},
    written: [],
    headersFlushed: false,
    failNextWrite: false,
    setHeader(name, value) {
      res.headers[name] = value;
    },
    flushHeaders() {
      res.headersFlushed = true;
    },
    write(chunk) {
      if (res.failNextWrite) throw new Error("write after end");
      res.written.push(chunk);
      return true;
    },
    req: {
      on(event, cb) {
        if (event === "close") closeHandler = cb;
      },
      emitClose() {
        closeHandler?.();
      },
    },
  };
  return res;
}

describe("pushMessage / getMessages", () => {
  it("assigns incrementing ids scoped to a session", () => {
    const sessionId = uniqueSessionId();
    const id1 = pushMessage(sessionId, { type: "text_delta", text: "a" });
    const id2 = pushMessage(sessionId, { type: "text_delta", text: "b" });
    expect(id1).toBe(1);
    expect(id2).toBe(2);
  });

  it("keeps id sequences independent across sessions", () => {
    const a = uniqueSessionId();
    const b = uniqueSessionId();
    pushMessage(a, { type: "text_delta", text: "a1" });
    pushMessage(a, { type: "text_delta", text: "a2" });
    const bId = pushMessage(b, { type: "text_delta", text: "b1" });
    expect(bId).toBe(1);
  });

  it("returns messages after the given id", () => {
    const sessionId = uniqueSessionId();
    pushMessage(sessionId, { type: "text_delta", text: "a" });
    pushMessage(sessionId, { type: "text_delta", text: "b" });
    pushMessage(sessionId, { type: "text_delta", text: "c" });
    const messages = getMessages(sessionId, 1);
    expect(messages).toEqual([
      { id: 2, type: "text_delta", text: "b" },
      { id: 3, type: "text_delta", text: "c" },
    ]);
  });

  it("returns an empty array for an unknown session", () => {
    expect(getMessages("no-such-session", 0)).toEqual([]);
  });

  it("caps the ring buffer at 500 messages per session, dropping the oldest", () => {
    const sessionId = uniqueSessionId();
    for (let i = 0; i < 501; i++) {
      pushMessage(sessionId, { type: "text_delta", text: `msg-${i}` });
    }
    const messages = getMessages(sessionId, 0);
    expect(messages).toHaveLength(500);
    // The very first message (msg-0, id 1) should have been evicted.
    expect(messages[0]).toEqual({ id: 2, type: "text_delta", text: "msg-1" });
    expect(messages.at(-1)).toEqual({ id: 501, type: "text_delta", text: "msg-500" });
  });
});

describe("broadcast", () => {
  it("writes SSE-framed data to all connected clients for a session", () => {
    const sessionId = uniqueSessionId();
    const client1 = createMockResponse();
    const client2 = createMockResponse();
    registerClient(sessionId, client1 as never, false);
    registerClient(sessionId, client2 as never, false);
    client1.written.length = 0;
    client2.written.length = 0;

    const event: SseEvent = { type: "text_delta", text: "hello" };
    broadcast(sessionId, event, 42);

    const expected = `id: 42\ndata: ${JSON.stringify(event)}\n\n`;
    expect(client1.written).toContain(expected);
    expect(client2.written).toContain(expected);
  });

  it("does nothing for a session with no connected clients", () => {
    const sessionId = uniqueSessionId();
    expect(() => broadcast(sessionId, { type: "text_delta", text: "x" }, 1)).not.toThrow();
  });

  it("silently drops clients whose write throws", () => {
    const sessionId = uniqueSessionId();
    const goodClient = createMockResponse();
    const deadClient = createMockResponse();
    registerClient(sessionId, goodClient as never, false);
    registerClient(sessionId, deadClient as never, false);
    deadClient.failNextWrite = true;

    const before = clientCount();
    broadcast(sessionId, { type: "text_delta", text: "x" }, 1);
    expect(clientCount()).toBe(before - 1);
  });
});

describe("emit", () => {
  it("pushes and broadcasts in one call", () => {
    const sessionId = uniqueSessionId();
    const client = createMockResponse();
    registerClient(sessionId, client as never, false);
    client.written.length = 0;

    emit(sessionId, { type: "status", state: "busy" });

    expect(getMessages(sessionId, 0)).toEqual([{ id: 1, type: "status", state: "busy" }]);
    expect(client.written.some((w) => w.includes("busy"))).toBe(true);
  });

  it("is a no-op when sessionId is undefined", () => {
    expect(() => emit(undefined, { type: "status", state: "idle" })).not.toThrow();
  });
});

describe("registerClient", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("sends SSE headers and an initial :ok comment", () => {
    const sessionId = uniqueSessionId();
    const client = createMockResponse();
    registerClient(sessionId, client as never, false);

    expect(client.headers["Content-Type"]).toBe("text/event-stream");
    expect(client.headers["Cache-Control"]).toBe("no-cache");
    expect(client.headersFlushed).toBe(true);
    expect(client.written[0]).toBe(":ok\n\n");
  });

  it("replays buffered messages when needReplay is true", () => {
    const sessionId = uniqueSessionId();
    pushMessage(sessionId, { type: "text_delta", text: "replayed" });
    const client = createMockResponse();

    registerClient(sessionId, client as never, true);

    expect(client.written.some((w) => w.includes("replayed"))).toBe(true);
  });

  it("does not replay buffered messages when needReplay is false", () => {
    const sessionId = uniqueSessionId();
    pushMessage(sessionId, { type: "text_delta", text: "should-not-replay" });
    const client = createMockResponse();

    registerClient(sessionId, client as never, false);

    expect(client.written.some((w) => w.includes("should-not-replay"))).toBe(false);
  });

  it("sends a heartbeat every 15 seconds", () => {
    const sessionId = uniqueSessionId();
    const client = createMockResponse();
    registerClient(sessionId, client as never, false);
    client.written.length = 0;

    vi.advanceTimersByTime(15000);
    expect(client.written).toContain(":heartbeat\n\n");
  });

  it("removes the client and stops heartbeats on disconnect", () => {
    const sessionId = uniqueSessionId();
    const client = createMockResponse();
    registerClient(sessionId, client as never, false);
    const before = clientCount();

    client.req.emitClose();
    expect(clientCount()).toBe(before - 1);

    client.written.length = 0;
    vi.advanceTimersByTime(30000);
    expect(client.written).toEqual([]);
  });
});
