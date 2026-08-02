import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SseEvent } from "../types.js";

// ── A controllable async-iterable stand-in for the SDK's Query handle ──────
class FakeQuery<T> {
  private buffered: T[] = [];
  private waiters: { resolve: (r: IteratorResult<T>) => void; reject: (err: Error) => void }[] = [];
  private closed = false;
  private pendingError: Error | null = null;
  interrupt = vi.fn(async () => {
    this.close();
  });

  push(item: T): void {
    const waiter = this.waiters.shift();
    if (waiter) waiter.resolve({ value: item, done: false });
    else this.buffered.push(item);
  }

  /** Makes the next iterator .next() call reject, simulating the underlying
   * SDK process itself failing mid-stream (as opposed to a `result` message
   * reporting an application-level error). */
  fail(err: Error): void {
    const waiter = this.waiters.shift();
    if (waiter) waiter.reject(err);
    else this.pendingError = err;
  }

  close(): void {
    this.closed = true;
    while (this.waiters.length) this.waiters.shift()!.resolve({ value: undefined as never, done: true });
  }

  [Symbol.asyncIterator]() {
    return {
      next: (): Promise<IteratorResult<T>> => {
        if (this.pendingError) {
          const err = this.pendingError;
          this.pendingError = null;
          return Promise.reject(err);
        }
        if (this.buffered.length) return Promise.resolve({ value: this.buffered.shift()!, done: false });
        if (this.closed) return Promise.resolve({ value: undefined as never, done: true });
        return new Promise((resolve, reject) => this.waiters.push({ resolve, reject }));
      },
    };
  }
}

interface CanUseToolOptions {
  signal: AbortSignal;
  suggestions?: unknown[];
  toolUseID: string;
}
type CanUseTool = (toolName: string, input: Record<string, unknown>, options: CanUseToolOptions) => Promise<unknown>;
interface CapturedCall {
  options: {
    canUseTool?: CanUseTool;
    resume?: string;
    cwd?: string;
    model?: string;
    permissionMode?: string;
    maxTurns?: number;
    hooks?: { Notification?: { hooks: ((input: unknown) => Promise<unknown>)[] }[] };
    stderr?: (data: string) => void;
  };
  queue: FakeQuery<unknown>;
}

const calls: CapturedCall[] = [];
const queryMock = vi.fn((params: { prompt: string; options: CapturedCall["options"] }) => {
  const queue = new FakeQuery<unknown>();
  calls.push({ options: params.options, queue });
  return queue;
});

vi.mock("@anthropic-ai/claude-agent-sdk", () => ({
  query: (params: never) => queryMock(params),
}));

const { ClaudeSession } = await import("./session.js");

function latestCall(): CapturedCall {
  return calls[calls.length - 1];
}

function noopSignal(): AbortSignal {
  return new AbortController().signal;
}

describe("ClaudeSession", () => {
  let emit: ReturnType<typeof vi.fn<(sessionId: string | undefined, msg: SseEvent) => void>>;

  beforeEach(() => {
    calls.length = 0;
    queryMock.mockClear();
    emit = vi.fn();
  });

  describe("run()", () => {
    it("passes the documented query options", async () => {
      const session = new ClaudeSession(emit);
      await session.run("do a thing");

      const { options } = latestCall();
      expect(options.model).toBe("claude-opus-4-6");
      expect(options.permissionMode).toBe("acceptEdits");
      expect(options.maxTurns).toBe(50);
      expect(options.resume).toBeUndefined();
    });

    it("resumes with the locked session id on a later run", async () => {
      const session = new ClaudeSession(emit);
      await session.start("existing-session-id", "/tmp");
      await session.run("continue");

      expect(latestCall().options.resume).toBe("existing-session-id");
    });

    it("throws if the session is already busy", async () => {
      const session = new ClaudeSession(emit);
      await session.run("first");
      await expect(session.run("second")).rejects.toThrow("Session is busy");
    });

    it("sets the session id from the first message carrying one and emits busy", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      latestCall().queue.push({ type: "system", subtype: "init", session_id: "sess-1" });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("sess-1", { type: "status", state: "busy", sessionId: "sess-1" });
      });
      expect(session.id).toBe("sess-1");
    });

    it("does not overwrite an already-set session id from a later message", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", subtype: "init", session_id: "real-session" });
      await vi.waitFor(() => expect(session.id).toBe("real-session"));

      queue.push({ type: "system", subtype: "resume-hook", session_id: "ephemeral-id" });
      await vi.waitFor(() => expect(emit).toHaveBeenCalled());
      expect(session.id).toBe("real-session");
    });
  });

  describe("stream_event translation", () => {
    it("emits think_start/think_end around a thinking content block", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "thinking" } } });
      queue.push({ type: "stream_event", event: { type: "content_block_stop" } });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "think_start", sessionId: "s1" });
        expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "think_end", sessionId: "s1" });
      });
    });

    it("emits text_start/text_delta/text_end for a text content block", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "stream_event", event: { type: "content_block_start", content_block: { type: "text" } } });
      queue.push({ type: "stream_event", event: { type: "content_block_delta", delta: { type: "text_delta", text: "hello" } } });
      queue.push({ type: "stream_event", event: { type: "content_block_stop" } });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "text_start", sessionId: "s1" });
        expect(emit).toHaveBeenCalledWith("s1", { type: "text_delta", text: "hello" });
        expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "text_end", sessionId: "s1" });
      });
    });

    it("emits tool_start for a tool_use content block", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({
        type: "stream_event",
        event: { type: "content_block_start", content_block: { type: "tool_use", id: "tool-1", name: "Read" } },
      });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", { type: "tool_start", name: "Read", toolId: "tool-1" });
      });
    });

    it("accumulates token counts from message_start/message_delta into the periodic running_stats", async () => {
      vi.useFakeTimers();
      try {
        const session = new ClaudeSession(emit);
        await session.run("hi");
        const queue = latestCall().queue;
        queue.push({ type: "system", session_id: "s1" });
        queue.push({
          type: "stream_event",
          event: { type: "message_start", message: { usage: { input_tokens: 10, cache_read_input_tokens: 5, cache_creation_input_tokens: 2 } } },
        });
        queue.push({ type: "stream_event", event: { type: "message_delta", usage: { output_tokens: 7 } } });

        await vi.advanceTimersByTimeAsync(10000);

        expect(emit).toHaveBeenCalledWith(
          "s1",
          expect.objectContaining({ type: "running_stats", inputTokens: 17, outputTokens: 7 }),
        );
      } finally {
        vi.useRealTimers();
      }
    });
  });

  describe("assistant/user tool pairing", () => {
    it("emits tool_end with paired input/output once the tool_result arrives", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({
        type: "assistant",
        message: { content: [{ type: "tool_use", id: "tool-1", name: "Read", input: { file_path: "a.ts" } }] },
      });
      queue.push({
        type: "user",
        message: { content: [{ type: "tool_result", tool_use_id: "tool-1", content: "file contents" }] },
      });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", {
          type: "tool_end",
          name: "Read",
          toolId: "tool-1",
          summary: "Read a.ts",
          detail: { input: { file_path: "a.ts" }, output: "file contents" },
        });
      });
    });

    it("joins an array of text blocks for tool_result content", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "assistant", message: { content: [{ type: "tool_use", id: "tool-1", name: "Bash", input: {} }] } });
      queue.push({
        type: "user",
        message: {
          content: [
            {
              type: "tool_result",
              tool_use_id: "tool-1",
              content: [
                { type: "text", text: "line one" },
                { type: "text", text: "  " }, // blank block, should be filtered out
                { type: "text", text: "line two" },
              ],
            },
          ],
        },
      });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith(
          "s1",
          expect.objectContaining({ type: "tool_end", detail: { input: {}, output: "line one\nline two" } }),
        );
      });
    });

    it("falls back to an empty string for unrecognized tool_result content shapes", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "assistant", message: { content: [{ type: "tool_use", id: "tool-1", name: "Bash", input: {} }] } });
      queue.push({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: "tool-1", content: { weird: true } }] } });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", expect.objectContaining({ type: "tool_end", detail: { input: {}, output: "" } }));
      });
    });

    it("ignores a tool_result with no matching pending tool_use", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "user", message: { content: [{ type: "tool_result", tool_use_id: "unknown", content: "x" }] } });
      queue.close();

      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "idle", sessionId: "s1" }));
      expect(emit).not.toHaveBeenCalledWith("s1", expect.objectContaining({ type: "tool_end" }));
    });
  });

  describe("emitResult", () => {
    it("emits a success result", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({
        type: "result",
        subtype: "success",
        session_id: "s1",
        result: "All done",
        total_cost_usd: 0.05,
        num_turns: 2,
        duration_ms: 1000,
        modelUsage: { "claude-opus-4-6": { inputTokens: 10, outputTokens: 20 } },
      });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", {
          type: "result",
          success: true,
          text: "All done",
          sessionId: "s1",
          costUsd: 0.05,
          provider: "claude",
          turns: 2,
          durationMs: 1000,
          inputTokens: 10,
          outputTokens: 20,
        });
      });
    });

    it("maps an aborted-streaming error to 'Interrupted by user'", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({
        type: "result",
        subtype: "error_during_execution",
        terminal_reason: "aborted_streaming",
        session_id: "s1",
        errors: ["some raw SDK error"],
      });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith(
          "s1",
          expect.objectContaining({ type: "result", success: false, text: "Interrupted by user" }),
        );
      });
    });

    it("maps error_max_turns to a friendly message when no errors array is present", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "result", subtype: "error_max_turns", session_id: "s1", num_turns: 50, errors: [] });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith(
          "s1",
          expect.objectContaining({
            text: "Reached max turns limit (50 turns). Try breaking the task into smaller steps.",
          }),
        );
      });
    });

    it("maps error_max_budget_usd to a friendly message", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "result", subtype: "error_max_budget_usd", session_id: "s1", errors: [] });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", expect.objectContaining({ text: "Session budget exhausted." }));
      });
    });

    it("emits idle once the generator loop ends after a result", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "result", subtype: "success", session_id: "s1", result: "ok" });
      queue.close();

      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "idle", sessionId: "s1" }));
    });

    it("falls back to the raw errors array for an unrecognized error subtype", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({
        type: "result",
        subtype: "error_max_structured_output_retries",
        session_id: "s1",
        errors: ["structured output retry limit hit"],
      });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith(
          "s1",
          expect.objectContaining({ success: false, text: "structured output retry limit hit" }),
        );
      });
    });
  });

  describe("system api_retry notifications", () => {
    it("emits a notification for an api_retry system event", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      queue.push({ type: "system", subtype: "api_retry", attempt: 2, max_retries: 5, error_status: 529 });

      await vi.waitFor(() => {
        expect(emit).toHaveBeenCalledWith("s1", {
          type: "notification",
          title: "API Retry",
          message: "Retrying (2/5), HTTP 529...",
        });
      });
    });
  });

  describe("unrecognized message types", () => {
    it("ignores message types with no dedicated handler", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      // Wait for the busy status from this first message before clearing, so
      // the clear lands after that microtask rather than racing ahead of it.
      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith("s1", expect.objectContaining({ type: "status", state: "busy" })));
      emit.mockClear();

      queue.push({ type: "rate_limit_event", rate_limit_info: { status: "allowed" } });
      queue.close();

      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "idle", sessionId: "s1" }));
      expect(emit).toHaveBeenCalledTimes(1); // only the trailing idle status — the unknown message itself produced nothing
    });
  });

  describe("query iteration errors", () => {
    it("emits an error event and logs when the query iterator rejects", async () => {
      const errorLogSpy = vi.spyOn(console, "error").mockImplementation(() => {});
      try {
        const session = new ClaudeSession(emit);
        await session.run("hi");
        const queue = latestCall().queue;
        queue.push({ type: "system", session_id: "s1" });
        queue.fail(new Error("connection reset"));

        await vi.waitFor(() => {
          expect(emit).toHaveBeenCalledWith("s1", { type: "error", message: "connection reset" });
        });
        expect(session.busy).toBe(false);
      } finally {
        errorLogSpy.mockRestore();
      }
    });

    it("does not emit an error event for an AbortError", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const queue = latestCall().queue;
      queue.push({ type: "system", session_id: "s1" });
      emit.mockClear();

      const abortError = new Error("aborted");
      abortError.name = "AbortError";
      queue.fail(abortError);

      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith("s1", { type: "status", state: "idle", sessionId: "s1" }));
      expect(emit).not.toHaveBeenCalledWith("s1", expect.objectContaining({ type: "error" }));
    });
  });

  describe("Notification hook", () => {
    it("translates the SDK's Notification hook input into a notification event", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const notificationHook = latestCall().options.hooks!.Notification![0].hooks[0];

      await notificationHook({ title: "Heads up", message: "Something happened" });

      expect(emit).toHaveBeenCalledWith(undefined, { type: "notification", title: "Heads up", message: "Something happened" });
    });

    it("falls back to default title/message text when the hook input omits them", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const notificationHook = latestCall().options.hooks!.Notification![0].hooks[0];

      await notificationHook({});

      expect(emit).toHaveBeenCalledWith(undefined, { type: "notification", title: "Notice", message: "" });
    });
  });

  describe("stderr callback", () => {
    it("logs non-blank stderr output from the underlying CLI process", async () => {
      const errorLogSpy = vi.spyOn(console, "error").mockImplementation(() => {});
      try {
        const session = new ClaudeSession(emit);
        await session.run("hi");
        const stderr = latestCall().options.stderr!;

        stderr("something went wrong\n");
        expect(errorLogSpy).toHaveBeenCalledWith("[cli stderr] something went wrong");
      } finally {
        errorLogSpy.mockRestore();
      }
    });

    it("ignores whitespace-only stderr output", async () => {
      const errorLogSpy = vi.spyOn(console, "error").mockImplementation(() => {});
      try {
        const session = new ClaudeSession(emit);
        await session.run("hi");
        const stderr = latestCall().options.stderr!;

        stderr("   \n");
        expect(errorLogSpy).not.toHaveBeenCalled();
      } finally {
        errorLogSpy.mockRestore();
      }
    });
  });

  describe("canUseTool routing", () => {
    it("auto-allows a tool not in any gated category", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;
      const result = await canUseTool!("Read", { file_path: "a.ts" }, { signal: noopSignal(), toolUseID: "t1" });
      expect(result).toEqual({ behavior: "allow", updatedInput: { file_path: "a.ts" } });
    });

    it("auto-allows a safe Bash command without prompting", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;
      const result = await canUseTool!("Bash", { command: "git status" }, { signal: noopSignal(), toolUseID: "t1" });
      expect(result).toEqual({ behavior: "allow", updatedInput: { command: "git status" } });
      expect(emit).not.toHaveBeenCalledWith(undefined, expect.objectContaining({ type: "permission_request" }));
    });

    it("prompts for an unsafe Bash command and resolves on respondPermission('allow')", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;

      const pending = canUseTool!("Bash", { command: "touch newfile.txt" }, { signal: noopSignal(), toolUseID: "t1" });
      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith(undefined, expect.objectContaining({ type: "permission_request" })));
      session.respondPermission("allow");

      const result = await pending;
      expect(result).toEqual({ behavior: "allow", updatedInput: { command: "touch newfile.txt" } });
      expect(emit).toHaveBeenCalledWith(
        undefined,
        expect.objectContaining({ type: "permission_result", decision: "allowed" }),
      );
    });

    it("denies a gated tool call on respondPermission('deny')", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;

      const pending = canUseTool!("KillShell", { pid: 1 }, { signal: noopSignal(), toolUseID: "t1" });
      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith(undefined, expect.objectContaining({ type: "permission_request" })));
      session.respondPermission("deny");

      const result = await pending;
      expect(result).toEqual({ behavior: "deny", message: "Denied by user" });
    });

    it("remembers allowAlways decisions for the rest of the session", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;

      const first = canUseTool!("KillShell", { pid: 1 }, { signal: noopSignal(), toolUseID: "t1" });
      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith(undefined, expect.objectContaining({ type: "permission_request" })));
      session.respondPermission("allowAlways");
      await first;

      emit.mockClear();
      const second = await canUseTool!("KillShell", { pid: 2 }, { signal: noopSignal(), toolUseID: "t2" });
      expect(second).toEqual({ behavior: "allow", updatedInput: { pid: 2 } });
      expect(emit).not.toHaveBeenCalledWith(undefined, expect.objectContaining({ type: "permission_request" }));
    });

    it("routes AskUserQuestion through the question flow and resolves with structured answers", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;

      const questions = [{ question: "Tabs or spaces?", header: "Style", options: [{ label: "Spaces" }, { label: "Tabs" }] }];
      const pending = canUseTool!("AskUserQuestion", { questions }, { signal: noopSignal(), toolUseID: "t1" });
      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith(undefined, expect.objectContaining({ type: "user_question" })));
      session.respondQuestion("Spaces");

      const result = await pending;
      expect(result).toEqual({
        behavior: "allow",
        updatedInput: { questions, answers: { "Tabs or spaces?": "Spaces" } },
      });
      expect(emit).toHaveBeenCalledWith(undefined, {
        type: "question_answer",
        answers: { "Tabs or spaces?": "Spaces" },
      });
    });

    it("emits task_progress for TodoWrite and always allows it", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;

      const todos = [
        { status: "completed", content: "Step 1" },
        { status: "in_progress", content: "Step 2" },
      ];
      const result = await canUseTool!("TodoWrite", { todos }, { signal: noopSignal(), toolUseID: "t1" });

      expect(result).toEqual({ behavior: "allow", updatedInput: { todos } });
      expect(emit).toHaveBeenCalledWith(undefined, { type: "task_progress", completed: 1, total: 2, current: "Step 2" });
    });
  });

  describe("status getter", () => {
    it("reports idle before any run", () => {
      const session = new ClaudeSession(emit);
      expect(session.status).toBe("idle");
    });

    it("reports busy while a run is in flight", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      expect(session.status).toBe("busy");
    });

    it("reports awaiting while a permission request is pending", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;
      void canUseTool!("KillShell", {}, { signal: noopSignal(), toolUseID: "t1" });

      await vi.waitFor(() => expect(session.status).toBe("awaiting"));
    });
  });

  describe("prompt queueing", () => {
    it("dispatches an enqueued prompt once the current run ends", async () => {
      const session = new ClaudeSession(emit);
      await session.run("first");
      session.enqueue("second");

      const firstQueue = latestCall().queue;
      firstQueue.push({ type: "result", subtype: "success", session_id: "s1", result: "first done" });
      firstQueue.close();

      await vi.waitFor(() => expect(queryMock).toHaveBeenCalledTimes(2));
      expect(session.busy).toBe(true);
    });
  });

  describe("interrupt()", () => {
    it("calls interrupt on the underlying query handle", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { queue } = latestCall();

      session.interrupt();
      await vi.waitFor(() => expect(queue.interrupt).toHaveBeenCalledOnce());
    });

    it("is a no-op when there is no active run", () => {
      const session = new ClaudeSession(emit);
      expect(() => session.interrupt()).not.toThrow();
    });
  });

  describe("close()", () => {
    it("resets busy state and clears always-allowed tools", async () => {
      const session = new ClaudeSession(emit);
      await session.run("hi");
      const { canUseTool } = latestCall().options;
      const pending = canUseTool!("KillShell", {}, { signal: noopSignal(), toolUseID: "t1" });
      await vi.waitFor(() => expect(emit).toHaveBeenCalledWith(undefined, expect.objectContaining({ type: "permission_request" })));
      session.respondPermission("allowAlways");
      await pending;

      await session.close();

      expect(session.busy).toBe(false);
      expect(session.status).toBe("idle");
    });
  });
});
