import type { PermissionOption } from "../types.js";

// Reproduces even-terminal's canUseTool narrowing logic (docs/SYSTEM_DESIGN.md
// §6.1): with permissionMode "acceptEdits", file edits are pre-approved by the
// SDK itself and never reach here. Only these paths prompt the user.

const SAFE_BASH_PREFIX =
  /^\s*(ls|cat|head|tail|wc|pwd|echo|printf|date|whoami|which|where|type|file|stat|du|df|env|printenv|uname|hostname|id|git\s+(status|log|diff|branch|show|remote|rev-parse))\b/;

export function isSafeBashCommand(command: string): boolean {
  return SAFE_BASH_PREFIX.test(command.trim());
}

export const PERMISSION_GATED_TOOLS = new Set(["KillShell", "Config", "Mcp", "RemoteTrigger"]);

export function requiresPermissionConfirm(toolName: string): boolean {
  return PERMISSION_GATED_TOOLS.has(toolName);
}

interface PermissionSuggestion {
  type?: string;
  directories?: string[];
  rules?: { toolName?: string; ruleContent?: string }[];
  behavior?: string;
  mode?: string;
  destination?: string;
}

function quoteInline(value: unknown): string {
  return `\`${String(value)}\``;
}

function joinQuoted(values: string[]): string {
  return values.map(quoteInline).join(", ");
}

function describeDestination(destination: string | undefined): string {
  switch (destination) {
    case "session":
      return "for this session";
    case "projectSettings":
      return "for this project";
    case "localSettings":
      return "in local settings";
    case "userSettings":
      return "in user settings";
    case "cliArg":
      return "from CLI settings";
    default:
      return "for this project";
  }
}

export function describePermissionSuggestion(
  suggestions: PermissionSuggestion[] | undefined,
  placeholderText: string,
): string | null {
  const first = suggestions?.[0];
  if (!first) return null;

  if (first.type === "addDirectories" && Array.isArray(first.directories) && first.directories.length > 0) {
    return `Yes, and always allow access to ${joinQuoted(first.directories)} ${describeDestination(first.destination)}`;
  }
  if ((first.type === "addRules" || first.type === "replaceRules") && Array.isArray(first.rules) && first.rules.length > 0) {
    const rule = first.rules[0] ?? {};
    const toolName = String(rule.toolName ?? "this tool");
    const ruleContent = typeof rule.ruleContent === "string" && rule.ruleContent.trim() ? ` rule ${quoteInline(rule.ruleContent.trim())}` : "";
    const behavior = typeof first.behavior === "string" ? first.behavior : "allow";
    return `Yes, and always ${behavior} ${toolName}${ruleContent} ${describeDestination(first.destination)}`;
  }
  if (first.type === "setMode" && typeof first.mode === "string") {
    return `Yes, and use ${quoteInline(first.mode)} permission mode ${describeDestination(first.destination)}`;
  }
  return suggestions?.length ? placeholderText : null;
}

export function buildPermissionOptions(
  suggestions: PermissionSuggestion[] | undefined,
  description: string,
): PermissionOption[] {
  const options: PermissionOption[] = [{ text: "Yes", key: "allow" }];
  const alwaysText = describePermissionSuggestion(suggestions, description);
  if (alwaysText) {
    options.push({ text: alwaysText, key: "allowAlways" });
  }
  options.push({ text: "No", key: "deny" });
  return options;
}
