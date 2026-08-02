import { beforeEach, describe, expect, it, vi } from "vitest";
import { promisify } from "node:util";
import type { SseEvent } from "../types.js";

// ── Minimal controllable stand-in for the SDK's Query handle (see session.test.ts
// for the fuller version — this file only needs enough to drive prompt()/queueing). ──
class FakeQuery<T> {
  private buffered: T[] = [];
  private waiters: ((r: IteratorResult<T>) => void)[] = [];
  private closed = false;
  interrupt = vi.fn(async () => this.close());

  push(item: T): void {
    const waiter = this.waiters.shift();
    if (waiter) waiter({ value: item, done: false });
    else this.buffered.push(item);
  }
  close(): void {
    this.closed = true;
    while (this.waiters.length) this.waiters.shift()!({ value: undefined as never, done: true });
  }
  [Symbol.asyncIterator]() {
    return {
      next: (): Promise<IteratorResult<T>> => {
        if (this.buffered.length) return Promise.resolve({ value: this.buffered.shift()!, done: false });
        if (this.closed) return Promise.resolve({ value: undefined as never, done: true });
        return new Promise((resolve) => this.waiters.push(resolve));
      },
    };
  }
}

const queryCalls: FakeQuery<unknown>[] = [];
const queryMock = vi.fn((_params?: unknown) => {
  const q = new FakeQuery<unknown>();
  queryCalls.push(q);
  return q;
});
const listSessionsMock = vi.fn<(options?: unknown) => Promise<unknown[]>>();
const getSessionMessagesMock = vi.fn<(sessionId: string) => Promise<unknown[]>>();

vi.mock("@anthropic-ai/claude-agent-sdk", () => ({
  query: (params: unknown) => queryMock(params),
  listSessions: (options?: unknown) => listSessionsMock(options),
  getSessionMessages: (sessionId: string) => getSessionMessagesMock(sessionId),
}));

const execCustomMock = vi.fn<(command: string) => Promise<{ stdout: string; stderr: string }>>();
execCustomMock.mockResolvedValue({ stdout: "", stderr: "" });
vi.mock("node:child_process", () => ({
  exec: Object.assign(() => {}, { [promisify.custom]: (command: string) => execCustomMock(command) }),
}));

const { createClaudeProvider } = await import("./provider.js");

// session.start() awaits internal cleanup before query() is actually called,
// so query() lands a tick or two after prompt() is invoked, not synchronously.
async function latestQuery(): Promise<FakeQuery<unknown>> {
  await vi.waitFor(() => expect(queryCalls.length).toBeGreaterThan(0));
  return queryCalls[queryCalls.length - 1];
}

describe("createClaudeProvider", () => {
  let emit: ReturnType<typeof vi.fn<(sessionId: string | undefined, msg: SseEvent) => void>>;

  beforeEach(() => {
    queryCalls.length = 0;
    queryMock.mockClear();
    listSessionsMock.mockReset();
    getSessionMessagesMock.mockReset();
    execCustomMock.mockReset();
    execCustomMock.mockResolvedValue({ stdout: "", stderr: "" });
    emit = vi.fn();
  });

  describe("prompt()", () => {
    it("starts a new session and resolves the SDK-assigned session id", async () => {
      const provider = createClaudeProvider(emit);
      const promptResult = provider.prompt(undefined, "hello", "/project");

      (await latestQuery()).push({ type: "system", session_id: "sess-1" });
      const result = await promptResult;

      expect(result.sessionId).toBe("sess-1");
      expect(queryMock).toHaveBeenCalledOnce();
    });

    it("emits a user_prompt event once the session id resolves", async () => {
      const provider = createClaudeProvider(emit);
      const promptResult = provider.prompt(undefined, "hello there", undefined);
      (await latestQuery()).push({ type: "system", session_id: "sess-1" });
      await promptResult;

      expect(emit).toHaveBeenCalledWith("sess-1", { type: "user_prompt", text: "hello there" });
    });

    it("queues a second prompt on an already-busy session instead of starting a new run", async () => {
      const provider = createClaudeProvider(emit);
      const first = provider.prompt(undefined, "first", undefined);
      const query = await latestQuery();
      query.push({ type: "system", session_id: "sess-1" });
      const { sessionId } = await first;

      await provider.prompt(sessionId, "second", undefined);
      expect(queryMock).toHaveBeenCalledOnce(); // no second query() call while busy

      query.push({ type: "result", subtype: "success", session_id: "sess-1", result: "first done" });
      query.close();

      await vi.waitFor(() => expect(queryMock).toHaveBeenCalledTimes(2)); // queued prompt dispatched after idle
    });

    it("logs but does not throw if the underlying run() rejects", async () => {
      const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
      vi.useFakeTimers();
      try {
        queryMock.mockImplementationOnce(() => {
          throw new Error("SDK exploded");
        });
        const provider = createClaudeProvider(emit);
        const resultPromise = provider.prompt(undefined, "hello", undefined);
        await vi.advanceTimersByTimeAsync(10000); // waitForId's timeout, since no session id ever arrives
        await expect(resultPromise).resolves.toEqual({ sessionId: "" });
        expect(consoleErrorSpy).toHaveBeenCalledWith("[claude-provider] run failed: SDK exploded");
      } finally {
        vi.useRealTimers();
        consoleErrorSpy.mockRestore();
      }
    });

    it("resolves to an empty sessionId if the SDK never assigns one in time", async () => {
      vi.useFakeTimers();
      try {
        const provider = createClaudeProvider(emit);
        const resultPromise = provider.prompt(undefined, "hello", undefined);
        await vi.advanceTimersByTimeAsync(10000);
        const result = await resultPromise;
        expect(result.sessionId).toBe("");
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe("respondPermission / respondQuestion / interrupt", () => {
    it("is a no-op for an unknown session", () => {
      const provider = createClaudeProvider(emit);
      expect(() => provider.respondPermission("ghost", "allow")).not.toThrow();
      expect(() => provider.respondQuestion("ghost", "answer")).not.toThrow();
      expect(() => provider.interrupt("ghost")).not.toThrow();
    });

    it("delegates to the underlying session for a known session", async () => {
      const provider = createClaudeProvider(emit);
      const promptResult = provider.prompt(undefined, "hi", undefined);
      const query = await latestQuery();
      query.push({ type: "system", session_id: "sess-1" });
      await promptResult;

      provider.interrupt("sess-1");
      await vi.waitFor(() => expect(query.interrupt).toHaveBeenCalledOnce());
    });
  });

  describe("getStatus", () => {
    it("returns null for an unknown session", () => {
      const provider = createClaudeProvider(emit);
      expect(provider.getStatus("ghost")).toBeNull();
    });

    it("returns the session's state for a known session", async () => {
      const provider = createClaudeProvider(emit);
      const promptResult = provider.prompt(undefined, "hi", undefined);
      (await latestQuery()).push({ type: "system", session_id: "sess-1" });
      await promptResult;

      expect(provider.getStatus("sess-1")).toEqual({ state: "busy" });
    });
  });

  describe("listSessions", () => {
    it("maps SDK session info to our SessionInfo shape", async () => {
      listSessionsMock.mockResolvedValue([
        { sessionId: "s1", summary: "A summary", lastModified: 1700000000000, cwd: "/proj", customTitle: undefined, firstPrompt: "hi" },
      ]);
      const provider = createClaudeProvider(emit);
      const sessions = await provider.listSessions(10, "/proj");

      expect(sessions).toEqual([
        {
          id: "s1",
          title: "A summary",
          timestamp: new Date(1700000000000).toISOString(),
          cwd: "/proj",
          provider: "claude",
          status: null,
        },
      ]);
      expect(listSessionsMock).toHaveBeenCalledWith({ dir: "/proj", limit: 10 });
    });

    it("prefers customTitle, then summary, then firstPrompt", async () => {
      listSessionsMock.mockResolvedValue([
        { sessionId: "s1", customTitle: "Custom", summary: "Summary", firstPrompt: "First", lastModified: 0 },
      ]);
      const provider = createClaudeProvider(emit);
      const [session] = await provider.listSessions(10);
      expect(session.title).toBe("Custom");
    });

    it("truncates long titles to 64 characters", async () => {
      listSessionsMock.mockResolvedValue([{ sessionId: "s1", summary: "x".repeat(100), lastModified: 0 }]);
      const provider = createClaudeProvider(emit);
      const [session] = await provider.listSessions(10);
      expect(session.title).toHaveLength(64);
    });

    it("omits the dir filter when no cwd is given", async () => {
      listSessionsMock.mockResolvedValue([]);
      const provider = createClaudeProvider(emit);
      await provider.listSessions(5);
      expect(listSessionsMock).toHaveBeenCalledWith({ limit: 5 });
    });
  });

  describe("getInfo", () => {
    it("returns version and account info on success", async () => {
      execCustomMock.mockImplementation(async (command: string) => {
        if (command === "claude --version") return { stdout: "1.2.3 (Claude Code)\n", stderr: "" };
        return { stdout: JSON.stringify({ email: "a@b.com", orgName: "Acme", subscriptionType: "pro" }), stderr: "" };
      });
      const provider = createClaudeProvider(emit);
      const info = await provider.getInfo();

      expect(info.version).toBe("1.2.3");
      expect(info.account).toEqual({ email: "a@b.com", organization: "Acme", subscriptionType: "pro" });
      expect(info.model).toBe("Claude Opus 4.6");
    });

    it("falls back to Unknown version when the CLI call fails", async () => {
      execCustomMock.mockRejectedValue(new Error("command not found"));
      const provider = createClaudeProvider(emit);
      const info = await provider.getInfo();
      expect(info.version).toBe("Unknown");
      expect(info.account).toEqual({});
    });

    it("returns an empty account when auth status is unparsable", async () => {
      execCustomMock.mockImplementation(async (command: string) => {
        if (command === "claude --version") return { stdout: "1.0.0", stderr: "" };
        return { stdout: "not json", stderr: "" };
      });
      const provider = createClaudeProvider(emit);
      const info = await provider.getInfo();
      expect(info.account).toEqual({});
      expect(info.version).toBe("1.0.0");
    });
  });

  describe("getHistory", () => {
    it("extracts text blocks and applies the requested limit", async () => {
      getSessionMessagesMock.mockResolvedValue([
        { type: "user", message: { content: [{ type: "text", text: "one" }] } },
        { type: "assistant", message: { content: [{ type: "text", text: "two" }] } },
        { type: "assistant", message: { content: [{ type: "tool_use", name: "Read" }] } },
      ]);
      const provider = createClaudeProvider(emit);
      const history = await provider.getHistory("s1", 10);

      expect(history).toEqual([
        { role: "user", text: "one" },
        { role: "assistant", text: "two" },
      ]);
    });

    it("caps the returned history at 10 items even for a larger limit", async () => {
      const messages = Array.from({ length: 15 }, (_, i) => ({
        type: "user",
        message: { content: [{ type: "text", text: `msg-${i}` }] },
      }));
      getSessionMessagesMock.mockResolvedValue(messages);
      const provider = createClaudeProvider(emit);
      const history = await provider.getHistory("s1", 50);

      expect(history).toHaveLength(10);
      expect(history[0].text).toBe("msg-5"); // last 10, in order
      expect(history.at(-1)?.text).toBe("msg-14");
    });

    it("ignores messages with no content array", async () => {
      getSessionMessagesMock.mockResolvedValue([{ type: "system", message: {} }]);
      const provider = createClaudeProvider(emit);
      const history = await provider.getHistory("s1", 10);
      expect(history).toEqual([]);
    });
  });
});
