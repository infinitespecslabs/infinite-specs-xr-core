// Human-readable one-liners for tool_start/tool_end/permission_request, e.g.
// "Edit api.ts +15 lines". Style + per-tool cases match even-terminal's own
// summarizeClaudeToolCall (reverse-engineered, see docs/SYSTEM_DESIGN.md §6),
// reproduced fresh here rather than transcribed line-for-line.

function fileName(filePath: unknown): string {
  if (typeof filePath !== "string" || !filePath) return "file";
  const parts = filePath.split(/[\\/]/);
  return parts[parts.length - 1] || filePath;
}

function oneLine(value: unknown): string {
  if (value === null || value === undefined) return "";
  return String(value).replace(/\s+/g, " ").trim();
}

function truncate(value: unknown, max: number): string {
  const text = oneLine(value);
  if (!text) return "";
  return text.length > max ? text.slice(0, max) + "..." : text;
}

export function summarizeToolCall(toolName: string, input: Record<string, unknown>): string {
  switch (toolName) {
    case "Bash": {
      const description = truncate(input.description ?? input.command ?? "command", 50);
      return `Bash ${description}`;
    }
    case "Read": {
      const name = fileName(input.file_path);
      const offset = input.offset as number | undefined;
      const limit = input.limit as number | undefined;
      if (offset !== undefined && limit !== undefined) return `Read ${name} (lines ${offset}-${offset + limit})`;
      if (limit !== undefined) return `Read ${name} (${limit} lines)`;
      return `Read ${name}`;
    }
    case "Edit": {
      const name = fileName(input.file_path);
      const newStr = input.new_string as string | undefined;
      const oldStr = input.old_string as string | undefined;
      const added = newStr ? newStr.split("\n").length : 0;
      const removed = oldStr ? oldStr.split("\n").length : 0;
      const delta = added - removed;
      if (delta > 0) return `Edit ${name} +${delta} lines`;
      if (delta < 0) return `Edit ${name} ${delta} lines`;
      return `Edit ${name} ~${added} lines`;
    }
    case "Write": {
      const name = fileName(input.file_path);
      const content = input.content as string | undefined;
      const lines = content ? content.split("\n").length : 0;
      return `Write ${name} (${lines} lines)`;
    }
    case "Glob":
      return `Glob ${truncate(input.pattern, 40)}`;
    case "Grep":
      return `Grep "${truncate(input.pattern, 25)}"`;
    case "Agent":
      return `Agent ${truncate(input.description ?? "", 40)}`;
    case "TodoWrite":
      return "TodoWrite update tasks";
    case "WebSearch":
      return `Search "${truncate(input.query ?? "", 30)}"`;
    case "WebFetch":
      return `Fetch ${truncate(input.url ?? "", 40)}`;
    case "KillShell":
      return `Kill process ${input.pid ?? ""}`.trim();
    case "Config":
      return `Config ${input.action ?? ""} ${truncate(input.key ?? "", 30)}`.trim();
    case "Mcp":
      return `MCP ${input.server_name ?? ""}.${truncate(input.tool_name ?? "", 30)}`;
    case "RemoteTrigger":
      return `Trigger ${input.action ?? "manage"}`;
    case "ExitPlanMode":
      return "ExitPlanMode";
    case "ListMcpResources":
      return "ListMcpResources";
    case "ReadMcpResource":
      return `ReadMcpResource ${truncate(input.uri ?? "", 40)}`;
    case "TaskOutput":
      return `Agent ${truncate(input.task_id ?? "", 30)}`.trim();
    case "AskUserQuestion":
      return "AskUserQuestion";
    default: {
      const displayName = toolName.startsWith("Task") ? "Agent" : toolName;
      const detail = input.subject ?? input.description ?? input.content ?? input.action ?? "";
      const detailText = truncate(detail, 40);
      return detailText ? `${displayName} ${detailText}` : displayName;
    }
  }
}
