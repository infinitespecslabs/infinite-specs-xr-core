import { describe, expect, it } from "vitest";
import { summarizeToolCall } from "./summarize.js";

describe("summarizeToolCall", () => {
  it("summarizes Bash using description over command", () => {
    expect(summarizeToolCall("Bash", { description: "List files", command: "ls -la" })).toBe("Bash List files");
  });

  it("summarizes Bash falling back to command", () => {
    expect(summarizeToolCall("Bash", { command: "ls -la" })).toBe("Bash ls -la");
  });

  it("summarizes Bash with neither field", () => {
    expect(summarizeToolCall("Bash", {})).toBe("Bash command");
  });

  it("summarizes Read with offset and limit as a line range", () => {
    expect(summarizeToolCall("Read", { file_path: "/a/b/api.ts", offset: 200, limit: 50 })).toBe("Read api.ts (lines 200-250)");
  });

  it("summarizes Read with only limit", () => {
    expect(summarizeToolCall("Read", { file_path: "/a/b/api.ts", limit: 50 })).toBe("Read api.ts (50 lines)");
  });

  it("summarizes Read with neither offset nor limit", () => {
    expect(summarizeToolCall("Read", { file_path: "/a/b/api.ts" })).toBe("Read api.ts");
  });

  it("summarizes Read with no file_path", () => {
    expect(summarizeToolCall("Read", {})).toBe("Read file");
  });

  it("summarizes Edit with a net line increase", () => {
    expect(
      summarizeToolCall("Edit", { file_path: "api.ts", old_string: "a", new_string: "a\nb\nc" }),
    ).toBe("Edit api.ts +2 lines");
  });

  it("summarizes Edit with a net line decrease", () => {
    expect(
      summarizeToolCall("Edit", { file_path: "api.ts", old_string: "a\nb\nc", new_string: "a" }),
    ).toBe("Edit api.ts -2 lines");
  });

  it("summarizes Edit with no net change", () => {
    expect(summarizeToolCall("Edit", { file_path: "api.ts", old_string: "a", new_string: "b" })).toBe("Edit api.ts ~1 lines");
  });

  it("summarizes Write with a line count", () => {
    expect(summarizeToolCall("Write", { file_path: "api.ts", content: "a\nb\nc" })).toBe("Write api.ts (3 lines)");
  });

  it("summarizes Write with no content", () => {
    expect(summarizeToolCall("Write", { file_path: "api.ts" })).toBe("Write api.ts (0 lines)");
  });

  it("summarizes Glob", () => {
    expect(summarizeToolCall("Glob", { pattern: "**/*.ts" })).toBe("Glob **/*.ts");
  });

  it("summarizes Grep", () => {
    expect(summarizeToolCall("Grep", { pattern: "TODO" })).toBe('Grep "TODO"');
  });

  it("summarizes Agent", () => {
    expect(summarizeToolCall("Agent", { description: "Explore the codebase" })).toBe("Agent Explore the codebase");
  });

  it("summarizes TodoWrite as a constant label", () => {
    expect(summarizeToolCall("TodoWrite", { todos: [] })).toBe("TodoWrite update tasks");
  });

  it("summarizes WebSearch", () => {
    expect(summarizeToolCall("WebSearch", { query: "vitest vs jest" })).toBe('Search "vitest vs jest"');
  });

  it("summarizes WebFetch", () => {
    expect(summarizeToolCall("WebFetch", { url: "https://example.com" })).toBe("Fetch https://example.com");
  });

  it("summarizes KillShell with a pid", () => {
    expect(summarizeToolCall("KillShell", { pid: 1234 })).toBe("Kill process 1234");
  });

  it("summarizes KillShell without a pid", () => {
    expect(summarizeToolCall("KillShell", {})).toBe("Kill process");
  });

  it("summarizes Config", () => {
    expect(summarizeToolCall("Config", { action: "set", key: "theme" })).toBe("Config set theme");
  });

  it("summarizes Mcp", () => {
    expect(summarizeToolCall("Mcp", { server_name: "filesystem", tool_name: "read_file" })).toBe("MCP filesystem.read_file");
  });

  it("summarizes RemoteTrigger with an action", () => {
    expect(summarizeToolCall("RemoteTrigger", { action: "deploy" })).toBe("Trigger deploy");
  });

  it("summarizes RemoteTrigger with no action", () => {
    expect(summarizeToolCall("RemoteTrigger", {})).toBe("Trigger manage");
  });

  it("summarizes ExitPlanMode as a constant label", () => {
    expect(summarizeToolCall("ExitPlanMode", {})).toBe("ExitPlanMode");
  });

  it("summarizes ListMcpResources as a constant label", () => {
    expect(summarizeToolCall("ListMcpResources", {})).toBe("ListMcpResources");
  });

  it("summarizes ReadMcpResource", () => {
    expect(summarizeToolCall("ReadMcpResource", { uri: "mcp://server/resource" })).toBe("ReadMcpResource mcp://server/resource");
  });

  it("summarizes TaskOutput", () => {
    expect(summarizeToolCall("TaskOutput", { task_id: "task-42" })).toBe("Agent task-42");
  });

  it("summarizes AskUserQuestion as a constant label", () => {
    expect(summarizeToolCall("AskUserQuestion", { questions: [] })).toBe("AskUserQuestion");
  });

  it("renames Task-prefixed unknown tools to Agent", () => {
    expect(summarizeToolCall("TaskCreate", { subject: "Fix bug" })).toBe("Agent Fix bug");
  });

  it("renames Task-prefixed unknown tools to Agent with no detail", () => {
    expect(summarizeToolCall("TaskList", {})).toBe("Agent");
  });

  it("falls back to the raw tool name with detail for unknown non-Task tools", () => {
    expect(summarizeToolCall("SomeFutureTool", { description: "does a thing" })).toBe("SomeFutureTool does a thing");
  });

  it("falls back to just the raw tool name when there is no detail", () => {
    expect(summarizeToolCall("SomeFutureTool", {})).toBe("SomeFutureTool");
  });

  it("truncates long values with an ellipsis", () => {
    const longPattern = "x".repeat(60);
    const result = summarizeToolCall("Glob", { pattern: longPattern });
    expect(result).toBe(`Glob ${"x".repeat(40)}...`);
  });

  it("collapses whitespace in truncated values", () => {
    expect(summarizeToolCall("WebSearch", { query: "line one\nline   two" })).toBe('Search "line one line two"');
  });
});
