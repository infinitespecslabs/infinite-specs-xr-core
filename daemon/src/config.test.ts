import { afterEach, describe, expect, it } from "vitest";
import { parseArgs } from "./config.js";

const BASE_ARGV = ["/usr/bin/node", "/path/to/index.js"];
const ORIGINAL_ENV = { ...process.env };

describe("parseArgs", () => {
  afterEach(() => {
    process.env = { ...ORIGINAL_ENV };
  });

  it("applies documented defaults when no flags are given", () => {
    delete process.env.BRIDGE_TOKEN;
    delete process.env.EVEN_TERMINAL_NAME;
    const config = parseArgs(BASE_ARGV);

    expect(config.port).toBe(3456);
    expect(config.cwd).toBe(process.cwd());
    expect(config.verbose).toBe(false);
    expect(config.name).toBeUndefined();
    expect(config.logFile).toBeUndefined();
    expect(config.token).toMatch(/^[0-9a-f]{32}$/);
  });

  it("generates a different random token on each call when none is configured", () => {
    delete process.env.BRIDGE_TOKEN;
    const a = parseArgs(BASE_ARGV);
    const b = parseArgs(BASE_ARGV);
    expect(a.token).not.toBe(b.token);
  });

  it("falls back to BRIDGE_TOKEN env var when no --token flag is given", () => {
    process.env.BRIDGE_TOKEN = "env-token-123";
    const config = parseArgs(BASE_ARGV);
    expect(config.token).toBe("env-token-123");
  });

  it("prefers an explicit --token flag over BRIDGE_TOKEN", () => {
    process.env.BRIDGE_TOKEN = "env-token-123";
    const config = parseArgs([...BASE_ARGV, "--token", "flag-token"]);
    expect(config.token).toBe("flag-token");
  });

  it("parses --port and its alias -p", () => {
    expect(parseArgs([...BASE_ARGV, "--port", "9000"]).port).toBe(9000);
    expect(parseArgs([...BASE_ARGV, "-p", "9000"]).port).toBe(9000);
  });

  it("parses --cwd and its alias -d", () => {
    expect(parseArgs([...BASE_ARGV, "--cwd", "/tmp/project"]).cwd).toBe("/tmp/project");
    expect(parseArgs([...BASE_ARGV, "-d", "/tmp/project"]).cwd).toBe("/tmp/project");
  });

  it("parses --name and its alias -n, falling back to EVEN_TERMINAL_NAME", () => {
    delete process.env.EVEN_TERMINAL_NAME;
    expect(parseArgs([...BASE_ARGV, "--name", "My Glasses"]).name).toBe("My Glasses");
    expect(parseArgs([...BASE_ARGV, "-n", "My Glasses"]).name).toBe("My Glasses");

    process.env.EVEN_TERMINAL_NAME = "Env Name";
    expect(parseArgs(BASE_ARGV).name).toBe("Env Name");
  });

  it("parses --verbose", () => {
    expect(parseArgs([...BASE_ARGV, "--verbose"]).verbose).toBe(true);
  });

  it("parses --log-file", () => {
    expect(parseArgs([...BASE_ARGV, "--log-file", "/tmp/out.log"]).logFile).toBe("/tmp/out.log");
  });

  it("does not drop the first parsed flag (regression: double argv slice)", () => {
    // parseArgs relies on yargs' hideBin(), which expects the full
    // [execPath, scriptPath, ...args] argv — passing an already-sliced
    // array here previously caused the first real flag to be silently
    // dropped. This mirrors the exact bug found during Phase 1 verification.
    const config = parseArgs([...BASE_ARGV, "--port", "3457", "--token", "buildtest"]);
    expect(config.port).toBe(3457);
    expect(config.token).toBe("buildtest");
  });
});
