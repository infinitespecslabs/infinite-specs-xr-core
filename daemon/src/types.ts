// SSE event union + REST DTOs — mirrors docs/SYSTEM_DESIGN.md §2 exactly.

export type StatusState = "busy" | "idle" | "think_start" | "think_end" | "text_start" | "text_end";

export type PermissionDecision = "allow" | "allowAlways" | "deny";
export type PermissionResolution = "allowed" | "denied" | "always";

export interface PermissionOption {
  text: string;
  key: PermissionDecision;
}

export interface QuestionOption {
  label: string;
  description: string;
  preview: string;
}

export interface QuestionItem {
  question: string;
  header: string;
  options: QuestionOption[];
}

export type SseEvent =
  | { type: "status"; state: StatusState; sessionId?: string }
  | { type: "text_delta"; text: string }
  | { type: "tool_start"; name: string; toolId: string }
  | { type: "tool_end"; name: string; toolId: string; summary: string; detail: { input: unknown; output: string } }
  | {
      type: "permission_request";
      toolName: string;
      description: string;
      detail: string;
      toolUseId: string;
      options: PermissionOption[];
      suggestions: unknown[] | null;
    }
  | { type: "permission_result"; toolName: string; summary: string; decision: PermissionResolution }
  | { type: "user_question"; questions: QuestionItem[]; toolUseId: string }
  | { type: "question_answer"; answers: Record<string, string> }
  | { type: "user_prompt"; text: string }
  | { type: "running_stats"; durationMs: number; inputTokens: number; outputTokens: number }
  | { type: "task_progress"; completed: number; total: number; current: string }
  | { type: "notification"; title: string; message: string }
  | {
      type: "result";
      success: boolean;
      text: string;
      sessionId: string;
      costUsd: number;
      provider: "claude";
      turns: number;
      durationMs: number;
      inputTokens: number;
      outputTokens: number;
    }
  | { type: "error"; message: string };

// ── REST DTOs ────────────────────────────────────────────────

export interface SessionInfo {
  id: string;
  title: string;
  timestamp: string;
  cwd: string;
  provider: "claude";
  status: StatusState | "awaiting" | null;
}

export interface PromptRequestBody {
  text?: string;
  sessionId?: string;
  provider?: string;
  cwd?: string;
}

export interface PermissionResponseBody {
  sessionId?: string;
  provider?: string;
  decision?: PermissionDecision;
}

export interface QuestionResponseBody {
  sessionId?: string;
  provider?: string;
  answer?: string;
}

export interface InterruptRequestBody {
  sessionId?: string;
  provider?: string;
}

export interface StatusResponse {
  state: "busy" | "idle" | "awaiting";
  sessionId: string;
  provider: "claude";
}

export interface MessagesResponse {
  messages: ({ id: number } & SseEvent)[];
  state: string;
  sessionId: string;
  provider: string | null;
}

export interface InfoResponse {
  account: { email?: string; organization?: string; subscriptionType?: string };
  model: string;
  version: string;
  provider: "claude";
  error?: string;
}

export interface UpdateCheckResponse {
  currentVersion: string;
  latestVersion: string;
  updateAvailable: boolean;
}
