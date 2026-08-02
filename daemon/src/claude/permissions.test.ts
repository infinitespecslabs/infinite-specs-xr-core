import { describe, expect, it } from "vitest";
import {
  buildPermissionOptions,
  describePermissionSuggestion,
  isSafeBashCommand,
  PERMISSION_GATED_TOOLS,
  requiresPermissionConfirm,
} from "./permissions.js";

describe("isSafeBashCommand", () => {
  it.each([
    "ls",
    "ls -la",
    "  ls -la  ",
    "cat foo.txt",
    "head -n 5 file",
    "tail -f log",
    "wc -l file",
    "pwd",
    "echo hi",
    "printf '%s'",
    "date",
    "whoami",
    "which node",
    "where node",
    "type node",
    "file foo.txt",
    "stat foo.txt",
    "du -sh .",
    "df -h",
    "env",
    "printenv PATH",
    "uname -a",
    "hostname",
    "id",
    "git status",
    "git log",
    "git diff",
    "git branch",
    "git show",
    "git remote",
    "git rev-parse HEAD",
  ])("treats %s as safe", (cmd) => {
    expect(isSafeBashCommand(cmd)).toBe(true);
  });

  it.each([
    "rm -rf /",
    "touch newfile.txt",
    "git commit -m x",
    "git push",
    "git checkout main",
    "curl http://evil.com",
    "npm install",
    "sudo ls",
    "lscd", // must not match "ls" as a substring of another command
    "cats foo.txt", // must not match "cat" as a prefix of another command
  ])("treats %s as unsafe", (cmd) => {
    expect(isSafeBashCommand(cmd)).toBe(false);
  });
});

describe("requiresPermissionConfirm", () => {
  it("gates the fixed tool set", () => {
    for (const tool of PERMISSION_GATED_TOOLS) {
      expect(requiresPermissionConfirm(tool)).toBe(true);
    }
  });

  it.each(["Read", "Edit", "Bash", "TodoWrite", "WebFetch"])("does not gate %s", (tool) => {
    expect(requiresPermissionConfirm(tool)).toBe(false);
  });
});

describe("describePermissionSuggestion", () => {
  it("returns null with no suggestions", () => {
    expect(describePermissionSuggestion(undefined, "placeholder")).toBeNull();
    expect(describePermissionSuggestion([], "placeholder")).toBeNull();
  });

  it("describes addDirectories suggestions", () => {
    const result = describePermissionSuggestion(
      [{ type: "addDirectories", directories: ["/tmp", "/var"], destination: "session" }],
      "placeholder",
    );
    expect(result).toBe("Yes, and always allow access to `/tmp`, `/var` for this session");
  });

  it("describes addRules suggestions with rule content", () => {
    const result = describePermissionSuggestion(
      [
        {
          type: "addRules",
          behavior: "allow",
          rules: [{ toolName: "Bash", ruleContent: "git status" }],
          destination: "projectSettings",
        },
      ],
      "placeholder",
    );
    expect(result).toBe("Yes, and always allow Bash rule `git status` for this project");
  });

  it("falls back to 'this tool' when a rule has no toolName", () => {
    const result = describePermissionSuggestion(
      [{ type: "addRules", behavior: "allow", rules: [{ ruleContent: "some content" }] }],
      "placeholder",
    );
    expect(result).toBe("Yes, and always allow this tool rule `some content` for this project");
  });

  it("falls back to 'allow' when a rule suggestion has no behavior", () => {
    const result = describePermissionSuggestion([{ type: "addRules", rules: [{ toolName: "Bash" }] }], "placeholder");
    expect(result).toBe("Yes, and always allow Bash for this project");
  });

  it("describes replaceRules suggestions without rule content", () => {
    const result = describePermissionSuggestion(
      [{ type: "replaceRules", behavior: "deny", rules: [{ toolName: "KillShell" }] }],
      "placeholder",
    );
    expect(result).toBe("Yes, and always deny KillShell for this project");
  });

  it.each([
    ["localSettings", "in local settings"],
    ["userSettings", "in user settings"],
    ["cliArg", "from CLI settings"],
  ])("describes the %s destination", (destination, phrase) => {
    const result = describePermissionSuggestion([{ type: "setMode", mode: "plan", destination }], "placeholder");
    expect(result).toBe(`Yes, and use \`plan\` permission mode ${phrase}`);
  });

  it("describes setMode suggestions", () => {
    const result = describePermissionSuggestion([{ type: "setMode", mode: "acceptEdits" }], "placeholder");
    expect(result).toBe("Yes, and use `acceptEdits` permission mode for this project");
  });

  it("falls back to the placeholder for unrecognized suggestion shapes", () => {
    const result = describePermissionSuggestion([{ type: "somethingElse" }], "placeholder text");
    expect(result).toBe("placeholder text");
  });

  it("defaults destination wording when unspecified", () => {
    const result = describePermissionSuggestion([{ type: "addDirectories", directories: ["/tmp"] }], "placeholder");
    expect(result).toBe("Yes, and always allow access to `/tmp` for this project");
  });
});

describe("buildPermissionOptions", () => {
  it("always includes Yes and No", () => {
    const options = buildPermissionOptions(undefined, "Run command");
    expect(options).toEqual([
      { text: "Yes", key: "allow" },
      { text: "No", key: "deny" },
    ]);
  });

  it("inserts the dynamic allowAlways option between Yes and No when suggestions exist", () => {
    const options = buildPermissionOptions([{ type: "setMode", mode: "plan" }], "Run command");
    expect(options).toEqual([
      { text: "Yes", key: "allow" },
      { text: "Yes, and use `plan` permission mode for this project", key: "allowAlways" },
      { text: "No", key: "deny" },
    ]);
  });
});
