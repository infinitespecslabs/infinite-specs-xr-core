#!/usr/bin/env node
import { parseArgs } from "./config.js";
import { startServer } from "./server.js";

// parseArgs uses yargs' hideBin() internally, which expects the full
// process.argv (including the node executable and script path) — do not
// pre-slice here, or the first two real flags get silently dropped.
startServer(parseArgs(process.argv));
