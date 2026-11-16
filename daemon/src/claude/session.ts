import { query, type Options, type PermissionResult, type Query, type SDKMessage } from "@anthropic-ai/claude-agent-sdk";
import { debugLog, errorLog, log, warnLog } from "../logger.js";
import { summarizeToolCall } from "./summarize.js";
import { buildPermissionOptions, isSafeBashCommand, requiresPermissionConfirm } from "./permissions.js";
import type { SseEvent } from "../types.js";

const RUNNING_STATS_INTERVAL_MS = 10000;
const QUESTION_TIMEOUT_MS = 120000;
const PERMISSION_TIMEOUT_MS = 60000;

// Auto-approved tools. Bare names here never reach canUseTool (the SDK warns
// CLAUDE_SDK_CAN_USE_TOOL_SHADOWED about that at startup; it is intended).
// The Task* tracking tools are listed because current models no longer get
// them by default — naming them here is what makes the CLI offer them, and
// they are what task_progress is derived from (docs/SYSTEM_DESIGN.md §6.1).
const ALLOWED_TOOLS = [
  "Read",
  "Edit",
  "Glob",
  "Grep",
  "Agent",
  "WebSearch",
  "WebFetch",
  "TaskOutput",
  "ExitPlanMode",
  "ListMcpResources",
  "ReadMcpResource",
  "TaskCreate",
  "TaskUpdate",
  "TaskList",
  "TaskGet",
];

// Minimal local shapes for the bits of the SDK's streaming/message content we
// actually read — the full Anthropic Beta message types are much larger than
// what this daemon needs to translate into our SSE event schema.
interface StreamContentBlock {
  type: string;
  name?: string;
  id?: string;
}
interface StreamDelta {
  type: string;
  text?: string;
}
interface StreamUsage {
  input_tokens?: number;
  cache_read_input_tokens?: number;
  cache_creation_input_tokens?: number;
  output_tokens?: number;
}
interface StreamEvent {
  type: string;
  content_block?: StreamContentBlock;
  delta?: StreamDelta;
  message?: { usage?: StreamUsage };
  usage?: StreamUsage;
}
interface ContentBlock {
  type: string;
  text?: string;
  id?: string;
  name?: string;
  input?: Record<string, unknown>;
  tool_use_id?: string;
  content?: string | { type: string; text?: string }[];
}

interface PendingToolCall {
  name: string;
  input: Record<string, unknown>;
}

interface TrackedTask {
  subject: string;
  activeForm?: string;
  status: string;
}

export interface ClaudeSessionOptions {
  /** Model to request. Omitted = whatever the host's Claude Code defaults to. */
  model?: string;
  /** Called with the model the CLI reports it is actually using. */
  onModel?: (model: string) => void;
}

type PermissionWaiter = (value: { allow: boolean; allowAlways: boolean }) => void;
type QuestionWaiter = (value: string) => void;

function extractTextContent(content: unknown): string {
  if (typeof content === "string") return content;
  if (Array.isArray(content)) {
    return (content as { type: string; text?: string }[])
      .filter((b) => b.type === "text" && b.text?.trim())
      .map((b) => b.text)
      .join("\n");
  }
  return "";
}

/** One instance per Claude Agent SDK conversation. Wraps query() and translates
 * SDK messages into our SSE event schema. Mirrors even-terminal's ClaudeSession
 * (docs/SYSTEM_DESIGN.md §6.1), reimplemented fresh in TypeScript. */
export class ClaudeSession {
  private sessionId?: string;
  private lockedCwd = process.cwd();
  private _busy = false;
  private busyEmitted = false;
  private turnStartMs = 0;
  private runningInputTokens = 0;
  private runningOutputTokens = 0;
  private currentMsgOutputTokens = 0;
  private statsTimer: ReturnType<typeof setInterval> | null = null;
  private queryHandle: Query | null = null;
  private runningQuery: Promise<void> | null = null;
  private pendingPermissions: PermissionWaiter[] = [];
  private pendingQuestions: QuestionWaiter[] = [];
  private alwaysAllowedTools = new Set<string>();
  private pendingToolCalls = new Map<string, PendingToolCall>();
  private currentBlockType: "thinking" | "text" | null = null;
  private idResolve: ((id: string) => void) | null = null;
  private idPromise: Promise<string> | null = null;
  private idReadyCallbacks: ((id: string) => void)[] = [];
  private promptQueue: string[] = [];
  private tasks = new Map<string, TrackedTask>();

  constructor(
    private readonly emit: (sessionId: string | undefined, msg: SseEvent) => void,
    private readonly sessionOptions: ClaudeSessionOptions = {},
  ) {}

  get id(): string | undefined {
    return this.sessionId;
  }

  get busy(): boolean {
    return this._busy;
  }

  /** "awaiting" is computed, never pushed as an SSE status event — only
   * surfaced via GET /api/status (docs/SYSTEM_DESIGN.md §2.1). */
  get status(): "awaiting" | "busy" | "idle" {
    if (this.pendingPermissions.length > 0 || this.pendingQuestions.length > 0) return "awaiting";
    return this._busy ? "busy" : "idle";
  }

  waitForId(timeoutMs = 10000): Promise<string> {
    if (this.sessionId) return Promise.resolve(this.sessionId);
    if (!this.idPromise) {
      this.idPromise = new Promise((resolve) => {
        this.idResolve = resolve;
      });
    }
    return Promise.race([
      this.idPromise,
      new Promise<string>((_, reject) => setTimeout(() => reject(new Error("Timed out waiting for session ID")), timeoutMs)),
    ]);
  }

  onIdReady(cb: (id: string) => void): void {
    if (this.sessionId) {
      cb(this.sessionId);
    } else {
      this.idReadyCallbacks.push(cb);
    }
  }

  private setSessionId(id: string): void {
    // Don't overwrite an existing ID — hook messages (e.g. SessionStart:resume)
    // carry their own ephemeral session_id that would otherwise clobber the
    // real conversation ID.
    if (this.sessionId) return;
    this.sessionId = id;
    this.idResolve?.(id);
    this.idResolve = null;
    for (const cb of this.idReadyCallbacks) cb(id);
    this.idReadyCallbacks = [];
  }

  private send(msg: SseEvent): void {
    this.emit(this.sessionId, msg);
  }

  private waitForUser<T>(queue: ((value: T) => void)[], signal: AbortSignal, timeoutMs: number, defaultValue: T): Promise<T> {
    return new Promise((resolve) => {
      let settled = false;
      const finish = (value: T) => {
        if (settled) return;
        settled = true;
        clearTimeout(timer);
        signal.removeEventListener("abort", onAbort);
        const idx = queue.indexOf(finish as (value: T) => void);
        if (idx !== -1) queue.splice(idx, 1);
        resolve(value);
      };
      const timer = setTimeout(() => finish(defaultValue), timeoutMs);
      const onAbort = () => finish(defaultValue);
      signal.addEventListener("abort", onAbort, { once: true });
      queue.push(finish as (value: T) => void);
    });
  }

  respondPermission(decision: string): void {
    this.pendingPermissions.shift()?.({
      allow: decision === "allow" || decision === "allowAlways",
      allowAlways: decision === "allowAlways",
    });
  }

  respondQuestion(answer: string): void {
    this.pendingQuestions.shift()?.(answer);
  }

  private stopStatsTimer(): void {
    if (this.statsTimer) {
      clearInterval(this.statsTimer);
      this.statsTimer = null;
    }
  }

  private emitRunningStats(): void {
    if (!this._busy) {
      this.stopStatsTimer();
      return;
    }
    this.send({
      type: "running_stats",
      durationMs: this.turnStartMs ? Date.now() - this.turnStartMs : 0,
      inputTokens: this.runningInputTokens,
      outputTokens: this.runningOutputTokens + this.currentMsgOutputTokens,
    });
  }

  async start(sessionId: string | undefined, cwd: string | undefined): Promise<void> {
    await this.close();
    if (sessionId) {
      this.setSessionId(sessionId);
    }
    this.lockedCwd = cwd ?? process.cwd();
    log(`[session] Session configured: resume=${sessionId ?? "new"}, cwd=${this.lockedCwd}`);
  }

  async run(prompt: string): Promise<void> {
    if (this._busy) throw new Error("Session is busy");
    this._busy = true;
    this.busyEmitted = false;
    this.currentBlockType = null;
    this.turnStartMs = Date.now();
    this.runningInputTokens = 0;
    this.runningOutputTokens = 0;
    this.currentMsgOutputTokens = 0;
    this.stopStatsTimer();
    this.statsTimer = setInterval(() => this.emitRunningStats(), RUNNING_STATS_INTERVAL_MS);

    log(`[session] Launching query: resume=${this.sessionId ?? "new"}, cwd=${this.lockedCwd}`);
    const options: Options = {
      resume: this.sessionId,
      cwd: this.lockedCwd,
      ...(this.sessionOptions.model ? { model: this.sessionOptions.model } : {}),
      allowedTools: ALLOWED_TOOLS,
      permissionMode: "acceptEdits",
      canUseTool: (toolName, input, opts) => this.handleCanUseTool(toolName, input, opts),
      hooks: {
        Notification: [
          {
            hooks: [
              async (input) => {
                const notif = input as { title?: string; message?: string };
                const title = notif.title || "Notice";
                const message = notif.message || "";
                this.send({ type: "notification", title, message });
                return {};
              },
            ],
          },
        ],
      },
      includePartialMessages: true,
      maxTurns: 50,
      settingSources: ["user", "project"],
      stderr: (data: string) => {
        const trimmed = data.trim();
        if (trimmed) errorLog(`[cli stderr] ${trimmed}`);
      },
    };

    const q = query({ prompt, options });
    this.queryHandle = q;
    this.runningQuery = (async () => {
      try {
        for await (const msg of q) {
          this.processAndEmit(msg);
        }
      } catch (err) {
        const error = err as Error;
        if (error.name !== "AbortError") {
          errorLog(`[session] query error: ${error.message}`);
          this.send({ type: "error", message: error.message });
        }
      } finally {
        log("[session] Query process ended, cleaning up");
        this._busy = false;
        this.queryHandle = null;
        this.stopStatsTimer();
        if (this.promptQueue.length > 0) {
          this.dispatchNext();
        } else {
          this.send({ type: "status", state: "idle", sessionId: this.sessionId });
        }
      }
    })();
  }

  enqueue(prompt: string): void {
    this.promptQueue.push(prompt);
    log(`[session] Enqueued prompt (queue size: ${this.promptQueue.length})`);
  }

  private dispatchNext(): void {
    if (this.promptQueue.length === 0 || this._busy) return;
    const next = this.promptQueue.shift()!;
    log(`[session] Dispatching queued prompt (remaining: ${this.promptQueue.length})`);
    this.run(next).catch((err: Error) => errorLog(`[session] Failed to dispatch queued prompt: ${err.message}`));
  }

  interrupt(): void {
    this.queryHandle?.interrupt().catch(() => {});
  }

  async close(): Promise<void> {
    this.stopStatsTimer();
    this.pendingPermissions.length = 0;
    this.pendingQuestions.length = 0;
    this.promptQueue.length = 0;
    if (this.queryHandle) {
      this.queryHandle.interrupt().catch(() => {});
      this.queryHandle = null;
    }
    if (this.runningQuery) {
      await this.runningQuery.catch(() => {});
      this.runningQuery = null;
    }
    this._busy = false;
    this.alwaysAllowedTools.clear();
  }

  // ── Tool permission handling ──────────────────────────

  private async handleCanUseTool(
    toolName: string,
    input: Record<string, unknown>,
    options: { signal: AbortSignal; suggestions?: unknown[]; toolUseID: string },
  ): Promise<PermissionResult> {
    debugLog(`[session] canUseTool: ${toolName}`);

    if (toolName === "AskUserQuestion") {
      return this.handleAskUserQuestion(input, options.toolUseID, options.signal);
    }
    if (this.alwaysAllowedTools.has(toolName)) {
      return { behavior: "allow", updatedInput: input };
    }
    if (requiresPermissionConfirm(toolName)) {
      return this.handlePermissionConfirm(toolName, input, options.toolUseID, options.signal, options.suggestions);
    }
    if (toolName === "Bash") {
      const cmd = String(input.command || "").trim();
      if (isSafeBashCommand(cmd)) {
        return { behavior: "allow", updatedInput: input };
      }
      return this.handlePermissionConfirm(toolName, input, options.toolUseID, options.signal, options.suggestions);
    }
    if (toolName === "TodoWrite") {
      // Legacy path: older CLIs/models track work with a single TodoWrite call
      // carrying the whole list. Current ones use the Task* tools instead,
      // handled in trackTaskTool() from the message stream.
      const todos = (input.todos as { status: string; content?: string; activeForm?: string }[] | undefined) ?? [];
      this.emitTaskProgress(todos.map((t) => ({ subject: t.content ?? "", activeForm: t.activeForm, status: t.status })));
      return { behavior: "allow", updatedInput: input };
    }
    return { behavior: "allow", updatedInput: input };
  }

  // ── Task progress ─────────────────────────────────────

  private emitTaskProgress(tasks: TrackedTask[]): void {
    const total = tasks.length;
    if (total === 0) return;
    const completed = tasks.filter((t) => t.status === "completed").length;
    const active = tasks.find((t) => t.status === "in_progress");
    const current = active ? active.subject || active.activeForm || "" : completed === total ? "All done" : "";
    this.send({ type: "task_progress", completed, total, current });
  }

  /** Mirrors the CLI's task list from completed Task* tool calls. These tools
   * are auto-approved, so canUseTool never sees them; the tool_use input plus
   * the structured `tool_use_result` on the paired user message is the only
   * place the task ID and status transitions show up. */
  private trackTaskTool(call: PendingToolCall, result: unknown, outputText: string): void {
    const r = (result && typeof result === "object" ? result : {}) as Record<string, unknown>;
    switch (call.name) {
      case "TaskCreate": {
        const task = r.task as { id?: string; subject?: string } | undefined;
        const id = task?.id ?? /Task #(\S+) created/.exec(outputText)?.[1];
        if (!id) return;
        this.tasks.set(String(id), {
          subject: task?.subject ?? String(call.input.subject ?? ""),
          activeForm: call.input.activeForm as string | undefined,
          status: "pending",
        });
        break;
      }
      case "TaskUpdate": {
        if (r.success === false) return;
        const id = String(r.taskId ?? call.input.taskId ?? "");
        const status = call.input.status as string | undefined;
        if (status === "deleted") {
          if (!this.tasks.delete(id)) return;
          break;
        }
        const existing = this.tasks.get(id);
        // An update for a task we never saw created (e.g. a resumed session)
        // is only worth tracking once it tells us something displayable.
        if (!existing && !status) return;
        this.tasks.set(id, {
          subject: (call.input.subject as string | undefined) ?? existing?.subject ?? `Task #${id}`,
          activeForm: (call.input.activeForm as string | undefined) ?? existing?.activeForm,
          status: status ?? existing?.status ?? "pending",
        });
        break;
      }
      case "TaskList": {
        const listed = r.tasks as { id: string; subject: string; status: string }[] | undefined;
        if (!Array.isArray(listed)) return;
        const previous = this.tasks;
        this.tasks = new Map(listed.map((t) => [String(t.id), { subject: t.subject, activeForm: previous.get(String(t.id))?.activeForm, status: t.status }]));
        break;
      }
      default:
        return;
    }
    this.emitTaskProgress([...this.tasks.values()]);
  }

  private async handleAskUserQuestion(
    toolInput: Record<string, unknown>,
    toolUseID: string,
    signal: AbortSignal,
  ): Promise<PermissionResult> {
    const questions = (toolInput.questions as { question?: string; header?: string; options?: { label?: string; description?: string; preview?: string }[] }[]) ?? [];
    this.send({
      type: "user_question",
      questions: questions.map((q) => ({
        question: q.question || "",
        header: q.header || "",
        options: (q.options || []).map((o) => ({ label: o.label || "", description: o.description || "", preview: o.preview || "" })),
      })),
      toolUseId: toolUseID,
    });

    const answer = await this.waitForUser(this.pendingQuestions, signal, QUESTION_TIMEOUT_MS, "skip");
    let answers: Record<string, string> = {};
    try {
      answers = JSON.parse(answer);
    } catch {
      for (const q of questions) {
        answers[q.question || q.header || ""] = answer;
      }
    }
    this.send({ type: "question_answer", answers });
    return { behavior: "allow", updatedInput: { questions: toolInput.questions, answers } };
  }

  private async handlePermissionConfirm(
    toolName: string,
    toolInput: Record<string, unknown>,
    toolUseID: string,
    signal: AbortSignal,
    suggestions: unknown[] | undefined,
  ): Promise<PermissionResult> {
    const description = summarizeToolCall(toolName, toolInput);
    let detail = "";
    if (toolInput.command) detail = String(toolInput.command).slice(0, 200);
    else if (toolInput.file_path) detail = String(toolInput.file_path).slice(0, 200);
    else if (toolInput.url) detail = String(toolInput.url).slice(0, 200);
    else if (toolInput.prompt) detail = String(toolInput.prompt).slice(0, 200);
    else if (toolInput.query) detail = String(toolInput.query).slice(0, 200);
    else if (toolInput.content) detail = String(toolInput.content).slice(0, 100) + "...";

    const permissionOptions = buildPermissionOptions(suggestions as never, description);
    this.send({
      type: "permission_request",
      toolName,
      description,
      detail,
      toolUseId: toolUseID,
      options: permissionOptions,
      suggestions: (suggestions as unknown[]) ?? null,
    });

    const result = await this.waitForUser(this.pendingPermissions, signal, PERMISSION_TIMEOUT_MS, { allow: false, allowAlways: false });
    let resolution: "allowed" | "denied" | "always";
    if (result.allowAlways) {
      this.alwaysAllowedTools.add(toolName);
      resolution = "always";
    } else if (result.allow) {
      resolution = "allowed";
    } else {
      resolution = "denied";
    }
    this.send({ type: "permission_result", toolName, summary: description, decision: resolution });

    if (result.allow) {
      return { behavior: "allow", updatedInput: toolInput };
    }
    return { behavior: "deny", message: "Denied by user" };
  }

  // ── SDK message → SSE event translation ───────────────

  private processAndEmit(msg: SDKMessage): void {
    debugLog("[claude-sdk]", JSON.stringify(msg));
    const withSessionId = msg as { session_id?: string };
    if (withSessionId.session_id) {
      this.setSessionId(withSessionId.session_id);
    }
    if (!this.busyEmitted) {
      this.busyEmitted = true;
      this.send({ type: "status", state: "busy", sessionId: this.sessionId });
    }

    switch (msg.type) {
      case "stream_event":
        this.processStreamEvent(msg as unknown as { event: StreamEvent });
        break;
      case "assistant":
        this.processAssistant(msg as unknown as { message: { content: ContentBlock[] } });
        break;
      case "user":
        this.processUser(msg as unknown as { message: { content: ContentBlock[] }; tool_use_result?: unknown });
        break;
      case "result":
        this.emitResult(msg as unknown as ResultMessageShape);
        break;
      case "system":
        this.processSystem(msg as unknown as SystemMessageShape);
        break;
      default:
        break;
    }
  }

  private processSystem(m: SystemMessageShape): void {
    if (m.subtype === "init" && m.model) {
      this.sessionOptions.onModel?.(m.model);
    }
    if (m.subtype === "api_retry") {
      const attempt = m.attempt ?? 0;
      const maxRetries = m.max_retries ?? 0;
      const status = m.error_status;
      this.send({
        type: "notification",
        title: "API Retry",
        message: `Retrying (${attempt}/${maxRetries})${status ? `, HTTP ${status}` : ""}...`,
      });
    }
  }

  private processStreamEvent({ event }: { event: StreamEvent }): void {
    if (!event) return;

    if (event.type === "content_block_start") {
      const blockType = event.content_block?.type;
      if (blockType === "thinking" || blockType === "text") {
        this.currentBlockType = blockType;
        this.send({ type: "status", state: blockType === "thinking" ? "think_start" : "text_start", sessionId: this.sessionId });
      }
      if (blockType === "tool_use" && event.content_block?.name && event.content_block?.id) {
        this.send({ type: "tool_start", name: event.content_block.name, toolId: event.content_block.id });
      }
    }
    if (event.type === "content_block_stop" && this.currentBlockType) {
      this.send({ type: "status", state: this.currentBlockType === "thinking" ? "think_end" : "text_end", sessionId: this.sessionId });
      this.currentBlockType = null;
    }
    if (event.type === "content_block_delta" && event.delta?.type === "text_delta" && event.delta.text) {
      this.send({ type: "text_delta", text: event.delta.text });
    }
    if (event.type === "message_start" && event.message?.usage) {
      this.runningOutputTokens += this.currentMsgOutputTokens;
      this.currentMsgOutputTokens = 0;
      const usage = event.message.usage;
      this.runningInputTokens += (usage.input_tokens ?? 0) + (usage.cache_read_input_tokens ?? 0) + (usage.cache_creation_input_tokens ?? 0);
    }
    if (event.type === "message_delta" && event.usage) {
      this.currentMsgOutputTokens = event.usage.output_tokens ?? 0;
    }
  }

  private processAssistant({ message }: { message: { content: ContentBlock[] } }): void {
    const content = message?.content;
    if (!Array.isArray(content)) return;
    for (const block of content) {
      if (block.type === "tool_use" && block.id && block.name) {
        this.pendingToolCalls.set(block.id, { name: block.name, input: block.input ?? {} });
      }
    }
  }

  private processUser({ message, tool_use_result }: { message: { content: ContentBlock[] }; tool_use_result?: unknown }): void {
    const content = message?.content;
    if (!Array.isArray(content)) return;
    for (const block of content) {
      if (block.type !== "tool_result" || !block.tool_use_id) continue;
      const pending = this.pendingToolCalls.get(block.tool_use_id);
      if (!pending) continue;
      this.pendingToolCalls.delete(block.tool_use_id);
      const output = extractTextContent(block.content);
      this.trackTaskTool(pending, tool_use_result, output);
      this.send({
        type: "tool_end",
        name: pending.name,
        toolId: block.tool_use_id,
        summary: summarizeToolCall(pending.name, pending.input),
        detail: { input: pending.input, output },
      });
    }
  }

  private emitResult(r: ResultMessageShape): void {
    this.stopStatsTimer();
    let inputTokens = 0;
    let outputTokens = 0;
    if (r.modelUsage) {
      for (const m of Object.values(r.modelUsage)) {
        inputTokens += m.inputTokens ?? 0;
        outputTokens += m.outputTokens ?? 0;
      }
    }
    let resultText = r.result ?? "";
    if (r.subtype !== "success") {
      const errors = r.errors?.join("\n") ?? "";
      warnLog(`[session] Result error: subtype=${r.subtype} terminal_reason=${r.terminal_reason} errors=${JSON.stringify(r.errors)}`);
      if (r.subtype === "error_during_execution" && r.terminal_reason === "aborted_streaming") {
        resultText = "Interrupted by user";
      } else if (r.subtype === "error_max_turns") {
        resultText = errors || `Reached max turns limit (${r.num_turns ?? 0} turns). Try breaking the task into smaller steps.`;
      } else if (r.subtype === "error_max_budget_usd") {
        resultText = errors || "Session budget exhausted.";
      } else {
        resultText = errors;
      }
    }
    this.send({
      type: "result",
      success: r.subtype === "success",
      text: resultText,
      sessionId: r.session_id ?? this.sessionId ?? "",
      costUsd: r.total_cost_usd ?? 0,
      provider: "claude",
      turns: r.num_turns ?? 0,
      durationMs: r.duration_ms ?? 0,
      inputTokens,
      outputTokens,
    });
    // idle status is emitted by run()'s finally block once the generator loop ends.
  }
}

interface SystemMessageShape {
  subtype?: string;
  model?: string;
  attempt?: number;
  max_retries?: number;
  retry_delay_ms?: number;
  error_status?: number;
}

interface ResultMessageShape {
  subtype?: string;
  result?: string;
  session_id?: string;
  total_cost_usd?: number;
  num_turns?: number;
  duration_ms?: number;
  terminal_reason?: string;
  errors?: string[];
  modelUsage?: Record<string, { inputTokens?: number; outputTokens?: number }>;
}
