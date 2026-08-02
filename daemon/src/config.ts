import { randomBytes } from "node:crypto";
import yargs from "yargs";
import { hideBin } from "yargs/helpers";

export interface AppConfig {
  port: number;
  token: string;
  name?: string;
  cwd: string;
  verbose: boolean;
  logFile?: string;
}

export function parseArgs(argv: string[]): AppConfig {
  const parsed = yargs(hideBin(argv))
    .scriptName("xr-daemon")
    .option("port", { alias: "p", type: "number", default: 3456, describe: "Server port" })
    .option("token", {
      alias: "t",
      type: "string",
      describe: "Auth token (default: BRIDGE_TOKEN env var, else auto-generated)",
    })
    .option("name", { alias: "n", type: "string", describe: "Client display name" })
    .option("cwd", { alias: "d", type: "string", describe: "Project directory (where Claude Code sessions live)" })
    .option("verbose", { type: "boolean", default: false, describe: "Print raw SDK messages for debugging" })
    .option("log-file", { type: "string", describe: "Tee all logs to a file" })
    .help()
    .alias("help", "h")
    .parseSync();

  return {
    port: parsed.port,
    token: parsed.token ?? process.env.BRIDGE_TOKEN ?? randomBytes(16).toString("hex"),
    name: parsed.name ?? process.env.EVEN_TERMINAL_NAME,
    cwd: parsed.cwd ?? process.cwd(),
    verbose: parsed.verbose,
    logFile: parsed["log-file"],
  };
}
