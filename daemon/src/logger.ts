import { appendFileSync } from "node:fs";

let verbose = false;
let logFilePath: string | undefined;

export function configureLogger(options: { verbose: boolean; logFile?: string }): void {
  verbose = options.verbose;
  logFilePath = options.logFile;
}

function writeToLogFile(line: string): void {
  if (!logFilePath) return;
  try {
    appendFileSync(logFilePath, line + "\n");
  } catch {
    // Best-effort only — a broken log file must never take the daemon down.
  }
}

export function log(...args: unknown[]): void {
  const line = args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" ");
  console.log(...args);
  writeToLogFile(`[log] ${line}`);
}

export function debugLog(...args: unknown[]): void {
  if (!verbose) return;
  const line = args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" ");
  console.log(...args);
  writeToLogFile(`[debug] ${line}`);
}

export function warnLog(...args: unknown[]): void {
  const line = args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" ");
  console.warn(...args);
  writeToLogFile(`[warn] ${line}`);
}

export function errorLog(...args: unknown[]): void {
  const line = args.map((a) => (typeof a === "string" ? a : JSON.stringify(a))).join(" ");
  console.error(...args);
  writeToLogFile(`[error] ${line}`);
}
