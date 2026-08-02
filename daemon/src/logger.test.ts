import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const appendFileSyncMock = vi.fn();
vi.mock("node:fs", () => ({
  appendFileSync: (...args: unknown[]) => appendFileSyncMock(...args),
}));

const { configureLogger, debugLog, errorLog, log, warnLog } = await import("./logger.js");

describe("logger", () => {
  let consoleLogSpy: ReturnType<typeof vi.spyOn>;
  let consoleWarnSpy: ReturnType<typeof vi.spyOn>;
  let consoleErrorSpy: ReturnType<typeof vi.spyOn>;

  beforeEach(() => {
    consoleLogSpy = vi.spyOn(console, "log").mockImplementation(() => {});
    consoleWarnSpy = vi.spyOn(console, "warn").mockImplementation(() => {});
    consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});
    appendFileSyncMock.mockReset();
    configureLogger({ verbose: false });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("log() always prints to console.log", () => {
    log("hello", "world");
    expect(consoleLogSpy).toHaveBeenCalledWith("hello", "world");
  });

  it("warnLog() always prints to console.warn", () => {
    warnLog("careful");
    expect(consoleWarnSpy).toHaveBeenCalledWith("careful");
  });

  it("errorLog() always prints to console.error", () => {
    errorLog("boom");
    expect(consoleErrorSpy).toHaveBeenCalledWith("boom");
  });

  it("debugLog() is silent when verbose is off", () => {
    configureLogger({ verbose: false });
    debugLog("should not print");
    expect(consoleLogSpy).not.toHaveBeenCalled();
  });

  it("debugLog() prints when verbose is on", () => {
    configureLogger({ verbose: true });
    debugLog("should print");
    expect(consoleLogSpy).toHaveBeenCalledWith("should print");
  });

  it("does not touch the filesystem when no log file is configured", () => {
    configureLogger({ verbose: false });
    log("no file configured");
    expect(appendFileSyncMock).not.toHaveBeenCalled();
  });

  it("tees to the configured log file", () => {
    configureLogger({ verbose: false, logFile: "/tmp/daemon-test.log" });
    log("tee me");
    expect(appendFileSyncMock).toHaveBeenCalledWith("/tmp/daemon-test.log", expect.stringContaining("tee me"));
  });

  it("does not throw if the log file write fails", () => {
    configureLogger({ verbose: false, logFile: "/tmp/daemon-test.log" });
    appendFileSyncMock.mockImplementation(() => {
      throw new Error("disk full");
    });
    expect(() => log("resilient")).not.toThrow();
  });

  it("serializes non-string arguments as JSON in the file tee", () => {
    configureLogger({ verbose: false, logFile: "/tmp/daemon-test.log" });
    log("payload:", { a: 1 });
    expect(appendFileSyncMock).toHaveBeenCalledWith("/tmp/daemon-test.log", expect.stringContaining('{"a":1}'));
  });
});
